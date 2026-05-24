package com.example.conges.service;

import com.example.conges.dto.DemandeCongeRequest;
import com.example.conges.dto.DemandeCongeResponse;
import com.example.conges.dto.SoldeCongeResponse;
import com.example.conges.dto.StatistiquesRhResponse;
import com.example.conges.dto.config.WorkScheduleConfigResponse;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.Role;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.entity.ExceptionalLeaveConfig;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.EmployeeLeaveAllocationRepository;
import com.example.conges.repository.ExceptionalLeaveConfigRepository;
import com.example.conges.repository.JoursPrisParTypeProjection;
import com.example.conges.repository.UserRepository;
import javax.persistence.EntityNotFoundException;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDate;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Cycle de vie des demandes de congé et exposition des soldes à l’UI.
 * <p>Compose les règles pays ({@link CountryPolicyService}), le cas RTT France ({@link FranceRttLedgerService}) et les
 * allocations synchronisées depuis Dolibarr. Dolibarr stocke des quantités synchronisées ; les calculs métier par pays
 * restent ici.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CongeService {

    private static final EnumSet<StatutConge> STATUTS_COMPTABILISES_SOLDE =
            EnumSet.of(StatutConge.ACCEPTE, StatutConge.EN_ATTENTE);

    private static final EnumSet<StatutConge> STATUTS_HISTORIQUE =
            EnumSet.of(StatutConge.ACCEPTE, StatutConge.REFUSE, StatutConge.ANNULE);

    private final DemandeCongeRepository demandeCongeRepository;
    private final UserRepository userRepository;
    private final HistoryService historyService;
    private final WorkflowService workflowService;
    private final CountryPolicyService countryPolicyService;
    private final DolibarrService dolibarrService;
    private final EmployeeLeaveAllocationRepository employeeLeaveAllocationRepository;
    private final HrWorkScheduleService hrWorkScheduleService;
    private final HourlyLeaveCapEvaluator hourlyLeaveCapEvaluator;
    private final FranceRttLedgerService franceRttLedgerService;
    private final NotificationService notificationService;
    private final HrLeaveBalanceService hrLeaveBalanceService;
    private final ExceptionalLeaveConfigRepository exceptionalLeaveConfigRepository;
    private final HrHolidayService hrHolidayService;

    @Transactional
    public DemandeCongeResponse creerDemande(Long userId, DemandeCongeRequest request) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        TypeConge type = request.getTypeConge();
        String paysNorm = countryPolicyService.normalizeBusinessCountry(user.getPays());
        boolean fr = "FR".equals(paysNorm);

        if (type == TypeConge.COURTE_DUREE && !request.isDemandeSortieCourte()) {
            throw new IllegalArgumentException(
                    "Ce type de demande doit être créé depuis l'écran « Autorisation courte ».");
        }

        if (request.getDateFin().isBefore(request.getDateDebut())) {
            throw new IllegalArgumentException("La date de fin doit être après ou égale à la date de début");
        }

        // Blocage chevauchements (demandes en attente ou acceptées).
        long overlaps = demandeCongeRepository.countOverlappingDemandes(
                userId,
                EnumSet.of(StatutConge.EN_ATTENTE, StatutConge.ACCEPTE),
                request.getDateDebut(),
                request.getDateFin()
        );
        if (overlaps > 0) {
            throw new IllegalStateException("Chevauchement détecté : vous avez déjà une demande sur cette période.");
        }

        if (type == TypeConge.COURTE_DUREE && fr && !countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            throw new IllegalArgumentException("Les autorisations courtes sont désactivées pour votre pays.");
        }

        Set<LocalDate> holidays = hrHolidayService.getActiveHolidayDates(
                paysNorm, request.getDateDebut(), request.getDateFin());

        DemandeConge demande;

        if (type == TypeConge.EXCEPTIONNEL) {
            demande = buildExceptionalLeaveDemande(user, request, paysNorm);
        } else
        if (type == TypeConge.COURTE_DUREE) {
            LocalTime hd = request.getHeureDebut();
            LocalTime hf = request.getHeureFin();
            if (!fr) {
                hrWorkScheduleService.validatePermissionWithinWorkingHours(user, request);
                if (!request.getDateDebut().equals(request.getDateFin())) {
                    throw new IllegalArgumentException(
                            "Une autorisation courte hors France doit porter sur une seule journée.");
                }
                hourlyLeaveCapEvaluator.assertMonthlyCapForNewRequest(
                        userId, request.getDateDebut(), user.getPays());
                int mins = DemandeConge.minutesBetween(hd, hf);
                if (mins != CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES) {
                    throw new IllegalArgumentException(
                            "Durée invalide : %d minutes (exactement %d minutes attendues)."
                                    .formatted(mins, CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES));
                }
                demande = DemandeConge.builder()
                        .user(user)
                        .typeConge(TypeConge.COURTE_DUREE)
                        .dateDebut(request.getDateDebut())
                        .dateFin(request.getDateFin())
                        .nombreJours(0)
                        .motif(request.getMotif())
                        .statut(StatutConge.EN_ATTENTE)
                        .permissionHeureDebut(hd)
                        .permissionHeureFin(hf)
                        .dureePermissionMinutes(mins)
                        .startHalfDay(null)
                        .endHalfDay(null)
                        .build();
            } else {
                // France : 2 variantes sur le même type:
                // - RTT (jour / demi-journée): calc jours ouvrés, solde RTT.
                // - Autorisation courte 2h (comme TN/MA): horaires + cap mensuel.
                boolean hasHours = hd != null && hf != null;
                if (hasHours) {
                    hrWorkScheduleService.validatePermissionWithinWorkingHours(user, request);
                    if (!request.getDateDebut().equals(request.getDateFin())) {
                        throw new IllegalArgumentException(
                                "Une autorisation courte (2 h) doit porter sur une seule journée.");
                    }
                    hourlyLeaveCapEvaluator.assertMonthlyCapForNewRequest(
                            userId, request.getDateDebut(), user.getPays());
                    int mins = DemandeConge.minutesBetween(hd, hf);
                    if (mins != CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES) {
                        throw new IllegalArgumentException(
                                "Durée invalide : %d minutes (exactement %d minutes attendues)."
                                        .formatted(mins, CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES));
                    }
                    demande = DemandeConge.builder()
                            .user(user)
                            .typeConge(TypeConge.COURTE_DUREE)
                            .dateDebut(request.getDateDebut())
                            .dateFin(request.getDateFin())
                            .nombreJours(0)
                            .nombreJoursExact(0d)
                            .motif(request.getMotif())
                            .statut(StatutConge.EN_ATTENTE)
                            .permissionHeureDebut(hd)
                            .permissionHeureFin(hf)
                            .dureePermissionMinutes(mins)
                            .startHalfDay(null)
                            .endHalfDay(null)
                            .build();
                } else {
                    double joursExact = DemandeConge.calculerJoursOuvrablesExact(
                            request.getDateDebut(),
                            request.getDateFin(),
                            request.getStartHalfDay(),
                            request.getEndHalfDay(),
                            holidays);
                    if (joursExact <= 0d) {
                        throw new IllegalArgumentException("Aucun jour ouvrable dans la période choisie");
                    }
                    verifierSoldeDisponibleExact(userId, TypeConge.COURTE_DUREE, joursExact);
                    demande = DemandeConge.builder()
                            .user(user)
                            .typeConge(TypeConge.COURTE_DUREE)
                            .dateDebut(request.getDateDebut())
                            .dateFin(request.getDateFin())
                            .nombreJours((int) Math.round(joursExact))
                            .nombreJoursExact(joursExact)
                            .motif(request.getMotif())
                            .statut(StatutConge.EN_ATTENTE)
                            .permissionHeureDebut(null)
                            .permissionHeureFin(null)
                            .dureePermissionMinutes(null)
                            .startHalfDay(request.getStartHalfDay())
                            .endHalfDay(request.getEndHalfDay())
                            .build();
                }
            }
        } else {
            double joursExact = DemandeConge.calculerJoursOuvrablesExact(
                    request.getDateDebut(),
                    request.getDateFin(),
                    request.getStartHalfDay(),
                    request.getEndHalfDay(),
                    holidays);
            if (joursExact <= 0d) {
                throw new IllegalArgumentException("Aucun jour ouvrable dans la période choisie");
            }
            verifierSoldeDisponibleExact(userId, type, joursExact);

            demande = DemandeConge.builder()
                    .user(user)
                    .typeConge(type)
                    .dateDebut(request.getDateDebut())
                    .dateFin(request.getDateFin())
                    .nombreJours((int) Math.round(joursExact))
                    .nombreJoursExact(joursExact)
                    .motif(request.getMotif())
                    .statut(StatutConge.EN_ATTENTE)
                    .startHalfDay(request.getStartHalfDay())
                    .endHalfDay(request.getEndHalfDay())
                    .build();
        }

        // Champ « Approuvé par » : pour compatibilité, auto-assign si absent.
        UserEntity approvedBy = resolveApprovedByAdmin(request.getApprovedByAdminId());
        demande.setApprovedBy(approvedBy);

        workflowService.initializeWorkflow(demande);

        DemandeConge saved = demandeCongeRepository.save(demande);
        Long dolibarrLeaveId = dolibarrService.pushLeaveRequest(saved);
        if (dolibarrLeaveId != null) {
            saved.setDolibarrLeaveRequestId(dolibarrLeaveId);
            saved = demandeCongeRepository.save(saved);
            historyService.recordDolibarrSync(user, saved, "OUTBOUND_CREATED");
        }
        log.info("Demande de congé créée id={} pour userId={}", saved.getId(), userId);

        historyService.recordCreation(user, saved);

        // Email de confirmation à l'employé
        notificationService.notifyDemandeCreated(user, saved);

        // Email aux Super Admins à la création (anti-doublon via History)
        notifySuperAdminsOnCreate(user, saved, approvedBy);

        // Email à l'approbateur désigné dans « Approuvé par »
        notifyApproverOnCreate(user, saved, approvedBy);

        return toResponse(saved);
    }

    private void notifyApproverOnCreate(UserEntity requester, DemandeConge demande, UserEntity approvedBy) {
        if (approvedBy == null || !org.springframework.util.StringUtils.hasText(approvedBy.getEmail())) {
            return;
        }
        try {
            notificationService.notifyPendingApproval(approvedBy, demande, requester);
        } catch (RuntimeException ex) {
            log.warn("SMTP : notification approbateur non envoyée pour la demande {} : {}", demande.getId(), ex.getMessage());
        }
    }

    private DemandeConge buildExceptionalLeaveDemande(UserEntity user, DemandeCongeRequest request, String paysNorm) {
        Long cfgId = request.getExceptionalLeaveConfigId();
        if (cfgId == null) {
            throw new IllegalArgumentException("Veuillez sélectionner un congé exceptionnel.");
        }
        ExceptionalLeaveConfig cfg = exceptionalLeaveConfigRepository.findById(cfgId)
                .orElseThrow(() -> new IllegalArgumentException("Congé exceptionnel introuvable."));
        String cfgCountry = countryPolicyService.normalizeBusinessCountry(cfg.getCountryCode());
        if (!cfgCountry.equalsIgnoreCase(paysNorm)) {
            throw new IllegalArgumentException("Congé exceptionnel invalide pour votre pays.");
        }
        if (!Boolean.TRUE.equals(cfg.getEnabled())) {
            throw new IllegalArgumentException("Ce congé exceptionnel est désactivé.");
        }
        Set<LocalDate> holidays = hrHolidayService.getActiveHolidayDates(
                paysNorm, request.getDateDebut(), request.getDateFin());
        double joursExact = DemandeConge.calculerJoursOuvrablesExact(
                request.getDateDebut(),
                request.getDateFin(),
                request.getStartHalfDay(),
                request.getEndHalfDay(),
                holidays);
        if (joursExact <= 0d) {
            throw new IllegalArgumentException("Aucun jour ouvrable dans la période choisie");
        }
        // Quota annuel par type exceptionnel (EN_ATTENTE + ACCEPTE).
        int year = request.getDateDebut().getYear();
        double usedOrPending = demandeCongeRepository.sumExceptionalDaysForUserAndConfig(
                user.getId(), cfgId, year, EnumSet.of(StatutConge.EN_ATTENTE, StatutConge.ACCEPTE));
        double quota = cfg.getDaysPerYear() == null ? 0d : Math.max(0d, cfg.getDaysPerYear());
        double remaining = Math.max(0d, quota - usedOrPending);
        if (joursExact - remaining > 1e-9) {
            throw new IllegalStateException(String.format(
                    "Solde congé exceptionnel insuffisant (%s) : %.2f j. demandé(s), %.2f disponible(s) sur %.2f.",
                    cfg.getLabel(), joursExact, remaining, quota));
        }
        return DemandeConge.builder()
                .user(user)
                .typeConge(TypeConge.EXCEPTIONNEL)
                .exceptionalLeaveConfigId(cfgId)
                .dateDebut(request.getDateDebut())
                .dateFin(request.getDateFin())
                .nombreJours((int) Math.round(joursExact))
                .nombreJoursExact(joursExact)
                .motif(request.getMotif())
                .statut(StatutConge.EN_ATTENTE)
                .startHalfDay(request.getStartHalfDay())
                .endHalfDay(request.getEndHalfDay())
                .build();
    }

    private UserEntity resolveApprovedByAdmin(Long approvedByAdminId) {
        if (approvedByAdminId != null) {
            UserEntity u = userRepository.findById(approvedByAdminId)
                    .orElseThrow(() -> new IllegalArgumentException("Super Admin introuvable."));
            if (u.getRole() != Role.ADMIN) {
                throw new IllegalArgumentException("Le champ « Approuvé par » doit être un Super Admin.");
            }
            return u;
        }
        List<UserEntity> admins = userRepository.findByRole(Role.ADMIN);
        if (admins == null || admins.isEmpty()) {
            return null;
        }
        return admins.get(0);
    }

    private void notifySuperAdminsOnCreate(UserEntity requester, DemandeConge demande, UserEntity approvedBy) {
        try {
            // L'approbateur reçoit déjà notifyPendingApproval — on l'exclut ici pour éviter le doublon.
            String approverEmail = (approvedBy != null && approvedBy.getEmail() != null)
                    ? approvedBy.getEmail().trim().toLowerCase() : null;
            List<UserEntity> admins = userRepository.findByRole(Role.ADMIN);
            List<String> recipients = (admins == null ? List.<UserEntity>of() : admins).stream()
                    .map(UserEntity::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .filter(e -> approverEmail == null || !e.trim().toLowerCase().equals(approverEmail))
                    .distinct()
                    .toList();
            if (recipients.isEmpty()) return;

            boolean first = historyService.recordSuperAdminsNotifiedOnce(
                    requester,
                    demande,
                    "recipients=" + String.join(",", recipients));
            if (!first) return;

            notificationService.notifyNewRequestToSuperAdmins(recipients, demande, requester, approvedBy);
        } catch (RuntimeException ex) {
            log.warn("SMTP : notification Super Admins non envoyée pour la demande {} : {}", demande.getId(), ex.getMessage());
        }
    }

    @Transactional
    public DemandeCongeResponse annulerDemande(Long demandeId, Long userId) {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));

        if (!demande.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Cette demande ne vous appartient pas");
        }
        if (demande.getStatut() != StatutConge.EN_ATTENTE) {
            throw new IllegalStateException("Seules les demandes en attente peuvent être annulées");
        }

        UserEntity user = demande.getUser();
        demande.setStatut(StatutConge.ANNULE);
        demande.setDateTraitement(LocalDateTime.now());
        DemandeConge saved = demandeCongeRepository.save(demande);
        
        log.info("Demande id={} annulée par userId={}", demandeId, userId);
        if (saved.getDolibarrLeaveRequestId() != null) {
            log.warn("Demande id={} annulée mais non synchronisée dans Dolibarr (dolibarrId={}). À annuler manuellement dans Dolibarr si besoin.",
                    demandeId, saved.getDolibarrLeaveRequestId());
        }

        // Enregistrer l'annulation dans l'historique
        historyService.recordCancellation(user, saved, "Demande annulée par l'employé");
        
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DemandeCongeResponse> getMesDemandes(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new EntityNotFoundException("Utilisateur introuvable");
        }
        return demandeCongeRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Liste des demandes pour l’historique employé avec filtres (année civile intersectée ; statut).
     * Les paramètres {@code statutRaw} correspondent aux valeurs envoyées par le frontend (accordée, attente…).
     */
    @Transactional(readOnly = true)
    public List<DemandeCongeResponse> getMesDemandesFiltrees(
            Long userId,
            Integer annee,
            String statutRaw) {
        return getMesDemandes(userId).stream()
                .filter(d -> filtreDemandeIntersecteAnnee(d, annee))
                .filter(d -> filtreDemandePourStatutParam(d.getStatut(), statutRaw))
                .toList();
    }

    private static boolean filtreDemandeIntersecteAnnee(DemandeCongeResponse d, Integer annee) {
        if (annee == null || annee < 1970 || annee > 2100) {
            return true;
        }
        LocalDate debut = d.getDateDebut();
        LocalDate fin = d.getDateFin();
        if (debut == null || fin == null) {
            return false;
        }
        LocalDate yStart = LocalDate.of(annee, 1, 1);
        LocalDate yEnd = LocalDate.of(annee, 12, 31);
        return !fin.isBefore(yStart) && !debut.isAfter(yEnd);
    }

    private static boolean filtreDemandePourStatutParam(
            StatutConge demandeStatut,
            String statutRaw) {
        return parseStatutFiltreListe(statutRaw)
                .map(s -> demandeStatut == s)
                .orElse(true);
    }

    /**
     * @return vide si aucun filtre ({@code tous} ou blanc), sinon statut métier correspondant au libellé API.
     */
    private static Optional<StatutConge> parseStatutFiltreListe(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String n = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replace('-', '_');
        if ("tous".equals(n)) {
            return Optional.empty();
        }
        if (Set.of(
                        "attente",
                        "en_attente",
                        "enattente",
                        "pending")
                .contains(n)) {
            return Optional.of(StatutConge.EN_ATTENTE);
        }
        if (Set.of(
                        "validee",
                        "accordee",
                        "accepted",
                        "accepte",
                        "approved")
                .contains(n)) {
            return Optional.of(StatutConge.ACCEPTE);
        }
        if (Set.of(
                        "refusee",
                        "refuse",
                        "rejected")
                .contains(n)) {
            return Optional.of(StatutConge.REFUSE);
        }
        if (Set.of(
                        "annulee",
                        "annule",
                        "canceled",
                        "cancelled")
                .contains(n)) {
            return Optional.of(StatutConge.ANNULE);
        }
        try {
            return Optional.of(StatutConge.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<DemandeCongeResponse> getHistorique(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new EntityNotFoundException("Utilisateur introuvable");
        }
        return demandeCongeRepository.findByUserId(userId).stream()
                .filter(d -> STATUTS_HISTORIQUE.contains(d.getStatut()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SoldeCongeResponse> calculerSolde(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));

        int annee = Year.now().getValue();
        Map<TypeConge, EmployeeLeaveAllocation> byAppType =
                loadAllocationsMappedToTypes(user, annee, false);

        List<SoldeCongeResponse> result = new ArrayList<>();
        for (TypeConge type : TypeConge.values()) {
            if (type == TypeConge.COURTE_DUREE
                    && countryPolicyService.isRttEnabledForCountry(user.getPays())
                    && franceRttLedgerService.isFranceRttTrackedForUi(user)
                    && franceRttLedgerService.isLocalLedgerActive()) {
                LocalDate au = LocalDate.now();
                Map<String, Object> snap = franceRttLedgerService.ledgerSnapshot(user, annee, au);
                if (!snap.isEmpty()) {
                    Number totNum = Optional.ofNullable((Number) snap.get("rtt_total")).orElse(0);
                    Number useNum = Optional.ofNullable((Number) snap.get("rtt_used")).orElse(0);
                    Number remNum = Optional.ofNullable((Number) snap.get("rtt_remaining")).orElse(0);
                    double tot = totNum.doubleValue();
                    double use = useNum.doubleValue();
                    double rem = remNum.doubleValue();
                    result.add(SoldeCongeResponse.builder()
                            .typeConge(type)
                            .totalJours((int) Math.round(tot))
                            .joursPris((int) Math.round(use))
                            .joursRestants((int) Math.floor(rem))
                            .totalExact(tot)
                            .prisExact(use)
                            .restantsExact(rem)
                            .build());
                    continue;
                }
            }

            if (type == TypeConge.COURTE_DUREE && !countryPolicyService.isRttEnabledForCountry(user.getPays())) {
                result.add(SoldeCongeResponse.builder()
                        .typeConge(type)
                        .totalJours(0)
                        .joursPris(0)
                        .joursRestants(0)
                        .build());
                continue;
            }
            EmployeeLeaveAllocation a = byAppType.get(type);
            if (a != null) {
                result.add(SoldeCongeResponse.builder()
                        .typeConge(type)
                        .totalJours(safeDays(a.getJoursInitiaux()))
                        .joursPris(safeDays(a.getJoursUtilises()))
                        .joursRestants(safeDays(a.getJoursDisponibles()))
                        .build());
            } else {
                // Fallback: si pas d'allocation existante, renvoyer 0 (ou quota pays si tu préfères)
                result.add(SoldeCongeResponse.builder()
                        .typeConge(type)
                        .totalJours(0)
                        .joursPris(0)
                        .joursRestants(0)
                        .build());
            }
        }

        return result;
    }

    /**
     * Réponse enrichie compatible frontend (soldes CP / courte durée / maladie, etc.).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSoldeResponseMap(Long userId) {
        UserEntity principal = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        List<SoldeCongeResponse> soldes = calculerSolde(userId);
        return buildSoldeApiMap(principal, soldes);
    }

    /**
     * Horaires actifs du pays métier de l’employé (même source que la validation des permissions courtes).
     */
    @Transactional(readOnly = true)
    public WorkScheduleConfigResponse getActiveWorkScheduleForUser(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        String country = countryPolicyService.normalizeBusinessCountry(user.getPays());
        return hrWorkScheduleService.getConfig(country, null);
    }

    private Map<String, Object> buildSoldeApiMap(UserEntity user, List<SoldeCongeResponse> soldes) {
        int paye = joursRestantsEnum(soldes, TypeConge.PAYE);
        boolean fr = "FR".equals(countryPolicyService.normalizeBusinessCountry(user.getPays()));
        int courte =
                soldeBlocPourType(soldes, TypeConge.COURTE_DUREE)
                        .filter(bl -> bl.getRestantsExact() != null)
                        .map(bl -> (int) Math.floor(bl.getRestantsExact()))
                        .orElseGet(
                                () ->
                                        countryPolicyService.isRttEnabledForCountry(user.getPays())
                                                ? joursRestantsEnum(soldes, TypeConge.COURTE_DUREE)
                                                : 0);
        int sans = joursRestantsEnum(soldes, TypeConge.SANS_SOLDE);

        final boolean dolibarrCommeReferentielSoldes =
                dolibarrService.isLeaveBalanceFromDolibarr(user);
        final int mal;
        final int malQuota;
        final boolean maladieNonDecompte;
        final String messageMaladie;
        if (dolibarrCommeReferentielSoldes) {
            mal = Math.max(0, joursRestantsEnum(soldes, TypeConge.MALADIE));
            malQuota = soldeBlocPourType(soldes, TypeConge.MALADIE)
                    .map(SoldeCongeResponse::getTotalJours)
                    .orElse(mal);
            maladieNonDecompte = false;
            /*
             * 0 jour = valeur Dolibarr (pas un calcul automatique pays dans l’app). Souvent : pas de ligne d’allocation
             * pour un type relié comme « maladie », pas de fk_user Dolibarr, ou solde vraiment à 0.
             */
            messageMaladie =
                    mal <= 0
                            ? "Congé maladie : valeur lue depuis Dolibarr uniquement. Si elle reste à 0 : vérifiez qu’un type congé maladie existe, qu’il est synchronisé, et que l’employé a bien une allocation (nb jours disponibles)."
                            : "";
        } else {
            malQuota = countryPolicyService.getAnnualQuota(user.getPays(), TypeConge.MALADIE);
            maladieNonDecompte = malQuota <= 0;
            int prisMal = compterJoursPrisOuReservesPourType(user.getId(), TypeConge.MALADIE);
            mal = maladieNonDecompte ? 0 : Math.max(0, malQuota - prisMal);
            messageMaladie =
                    maladieNonDecompte
                            ? "Congé maladie : suivi hors quota décompté dans l’application (consultez vos règles RH)."
                            : "";
        }

        Map<String, Object> m = new HashMap<>();
        m.put("soldeCongesPayes", paye);
        soldeBlocPourType(soldes, TypeConge.COURTE_DUREE)
                .map(SoldeCongeResponse::getRestantsExact)
                .ifPresent(re -> {
                    if (countryPolicyService.isRttEnabledForCountry(user.getPays())) {
                        m.put("soldeCourteDureeExact", re);
                    }
                });

        Map<String, Object> frRt = Collections.emptyMap();
        if (franceRttLedgerService.isFranceRttTrackedForUi(user)) {
            frRt = franceRttLedgerService.ledgerSnapshot(user, java.time.Year.now().getValue(), LocalDate.now());
        }
        if (!frRt.isEmpty()) {
            m.put("franceRtt", new HashMap<>(frRt));
        }

        m.put("soldeCourteDuree", courte);
        m.put("soldePermission", courte);
        m.put("soldeMaladie", mal);
        m.put("soldeSansSolde", sans);
        m.put("maladieQuotaReference", malQuota);
        m.put("maladieNonDecompte", maladieNonDecompte);
        m.put("messageMaladie", messageMaladie);

        if (!fr) {
            LocalDate moisCourant = LocalDate.now();
            LocalDate mDeb = moisCourant.withDayOfMonth(1);
            LocalDate mFin = moisCourant.withDayOfMonth(moisCourant.lengthOfMonth());
            long utiliseesOuAttente =
                    demandeCongeRepository.countShortHourlyLeavesInMonthForStatuses(
                            user.getId(),
                            EnumSet.of(StatutConge.ACCEPTE, StatutConge.EN_ATTENTE),
                            mDeb,
                            mFin);
            long acceptees =
                    demandeCongeRepository.countShortHourlyLeavesInMonthForStatus(
                            user.getId(), StatutConge.ACCEPTE, mDeb, mFin);
            int cap = CountryPolicyService.NON_FR_SHORT_LEAVE_MONTHLY_CAP;
            m.put("autorisationsCourtesMoisMaximum", cap);
            m.put("autorisationsCourtesMoisUtilisees", (int) utiliseesOuAttente);
            m.put("autorisationsCourtesMoisAcceptees", (int) acceptees);
            m.put(
                    "autorisationsCourtesMoisRestantes",
                    Math.max(0, cap - (int) utiliseesOuAttente));
        }

        Double totalAlloc =
                employeeLeaveAllocationRepository.getTotalJoursDisponibles(user, java.time.Year.now().getValue());
        int soldeTotalTousTypes = totalAlloc == null ? 0 : Math.max(0, (int) Math.round(totalAlloc));

        m.put("soldeTotalTousTypes", soldeTotalTousTypes);

        m.put("solde", paye);
        m.put("details", soldes);

        return m;
    }

    private static int joursRestantsEnum(List<SoldeCongeResponse> soldes, TypeConge t) {
        return soldes.stream()
                .filter(s -> s.getTypeConge() == t)
                .findFirst()
                .map(SoldeCongeResponse::getJoursRestants)
                .orElse(0);
    }

    /**
     * Compte les jours ouvrables entre deux dates inclusives.
     * Exclut le samedi et le dimanche. Les jours fériés ne sont pas pris en compte pour l'instant
     * (évolution prévue avec la gestion multi-pays).
     */
    public int calculerJoursOuvrables(LocalDate debut, LocalDate fin) {
        return DemandeConge.calculerJoursOuvrables(debut, fin);
    }

/**
 * Vérifie que l'utilisateur dispose d'assez de jours pour le type de congé demandé.
 * <p>Si Dolibarr est lié ({@link DolibarrService#isLeaveBalanceFromDolibarr}) : le plafond contrôlé est le solde
 * disponible lu depuis les allocations synchronisées (valeurs stockées côté Dolibarr), diminué des demandes
 * {@link StatutConge#EN_ATTENTE} dans cette app ; Dolibarr n’applique pas les règles pays — il fournit les quantités.</p>
 * <p>Sinon : plafonds pays calculés ici ({@link CountryPolicyService}) avec prise en compte des demandes
 * {@link StatutConge#ACCEPTE} ou {@link StatutConge#EN_ATTENTE}.</p>
 */
    @Transactional(readOnly = true)
    public void verifierSoldeDisponible(Long userId, TypeConge type, int joursDemandes) {
        verifierSoldeDisponibleExact(userId, type, (double) joursDemandes);
    }

    @Transactional(readOnly = true)
    public void verifierSoldeDisponibleExact(Long userId, TypeConge type, double joursDemandesExact) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        if (!(joursDemandesExact > 0d)) {
            throw new IllegalArgumentException("Le nombre de jours demandés doit être strictement positif");
        }

        if (type == TypeConge.SANS_SOLDE) {
            return;
        }
        if (type == TypeConge.EXCEPTIONNEL) {
            return;
        }

        if (type == TypeConge.COURTE_DUREE
                && "FR".equals(countryPolicyService.normalizeBusinessCountry(user.getPays()))
                && countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            if (franceRttLedgerService.governsFranceCourteRequests(user)) {
                franceRttLedgerService.assertSufficientFranceCourteExact(
                        user, Year.now().getValue(), LocalDate.now(), joursDemandesExact);
                return;
            }
        }

        /*
         * Lorsque l’utilisateur est lié à Dolibarr et que l’API est configurée, le solde utilisé pour
         * accepter une demande est celui lu depuis les allocations Dolibarr (avec retrait des autres
         * demandes déjà « en attente » dans cette appli mais pas encore reflétées côté Dolibarr).
         * La mise à jour définitive des soldes se fait ensuite dans Dolibarr à l’approbation.
         */
        if (dolibarrService.isLeaveBalanceFromDolibarr(user)) {
            verifierSoldeDepuisAllocationDolibarrExact(user, type, joursDemandesExact);
            return;
        }

        switch (type) {
            case PAYE -> {
                int prisOuReserve = compterJoursPrisOuReservesPourType(userId, TypeConge.PAYE);
                int ceiling = countryPolicyService.getPaidLeaveEntitlementCeilingForYear(user, Year.now().getValue());
                double restants = ceiling - prisOuReserve;
                if (joursDemandesExact - restants > 1e-9) {
                    throw new IllegalStateException(String.format(
                            "Solde de congés payés insuffisant : %.2f jour(s) ouvrable(s) demandé(s), %.2f disponible(s) sur %d (prorata pays)",
                            joursDemandesExact,
                            Math.max(0d, restants),
                            ceiling));
                }
            }
            case COURTE_DUREE -> {
                if (!countryPolicyService.isRttEnabledForCountry(user.getPays())) {
                    throw new IllegalArgumentException(
                            "Les sorties de courte durée ne sont pas disponibles pour votre pays.");
                }
                int prisOuReserve = compterJoursPrisOuReservesPourType(userId, TypeConge.COURTE_DUREE);
                int quota = countryPolicyService.getAnnualRttQuota(user.getPays());
                double restants = quota - prisOuReserve;
                if (joursDemandesExact - restants > 1e-9) {
                    throw new IllegalStateException(String.format(
                            "Solde sortie courte durée insuffisant (plafond %d j.) : %.2f j. demandé(s), %.2f disponible(s)",
                            quota,
                            joursDemandesExact,
                            Math.max(0d, restants)));
                }
            }
            case MALADIE -> {
                int quota = countryPolicyService.getAnnualQuota(user.getPays(), TypeConge.MALADIE);
                if (quota <= 0) {
                    return;
                }
                int prisOuReserve = compterJoursPrisOuReservesPourType(userId, TypeConge.MALADIE);
                double restants = quota - prisOuReserve;
                if (joursDemandesExact - restants > 1e-9) {
                    throw new IllegalStateException(String.format(
                            "Plafond annuel congé maladie (%d j.) : %.2f jour(s) demandé(s), %.2f disponible(s)",
                            quota,
                            joursDemandesExact,
                            Math.max(0d, restants)));
                }
            }
            case SANS_SOLDE -> {}
        }
    }

    private void verifierSoldeDepuisAllocationDolibarrExact(UserEntity user, TypeConge type, double joursDemandesExact) {
        if (type == TypeConge.COURTE_DUREE && !countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            throw new IllegalArgumentException(
                    "Les sorties de courte durée ne sont pas disponibles pour votre pays.");
        }
        int year = Year.now().getValue();
        Map<TypeConge, EmployeeLeaveAllocation> byAppType =
                loadAllocationsMappedToTypes(user, year, false);
        EmployeeLeaveAllocation allocation = byAppType.get(type);
        if (allocation == null) {
            throw new IllegalStateException(
                    "Solde Dolibarr introuvable pour ce type de congé. Vérifiez les types harmonisés (CP, RTT, maladie) et la liaison fk_user Dolibarr.");
        }
        double disponibleLu = Math.max(0d, safeDays(allocation.getJoursDisponibles()));
        double enAttenteCetteApplication =
                compterJoursDemandesEnAttenteSeulementPourTypeExact(user.getId(), type);
        double effectifPourNouvelleDemande = Math.max(0d, disponibleLu - enAttenteCetteApplication);
        if (joursDemandesExact - effectifPourNouvelleDemande > 1e-9) {
            throw new IllegalStateException(String.format(
                    "Solde insuffisant (Dolibarr) : %.2f jour(s) demandé(s), %.2f jour(s) encore disponibles au regard du solde synchronisé et de vos autres demandes en attente dans l’application.",
                    joursDemandesExact,
                    Math.max(0d, effectifPourNouvelleDemande)));
        }
    }

    private double compterJoursDemandesEnAttenteSeulementPourTypeExact(Long userId, TypeConge type) {
        List<JoursPrisParTypeProjection> rows = demandeCongeRepository.sumJoursPrisParTypePourUtilisateur(
                userId, EnumSet.of(StatutConge.EN_ATTENTE));
        return rows.stream()
                .filter(r -> r.getTypeConge() == type)
                .findFirst()
                .map(r -> r.getTotalJours().doubleValue())
                .orElse(0d);
    }

    /**
     * Lit les allocations locales pour l’année et applique la même grille métier que la page RH Soldes.
     * <p><b>Ne tire pas Dolibarr à chaque appel</b> : sinon chaque GET {@code /conge/solde} réécraserait la base locale
     * avec les valeurs distantes et annulerait les corrections RH ou les montants déjà persistés ici. La synchro
     * Dolibarr se fait au login, via {@code sync-all}, ou après flux métier explicitement.</p>
     *
     * @param refreshFromDolibarr si {@code true}, tente {@link DolibarrService#refreshAllocationsForUser} avant lecture
     *                            (réservé aux cas où un flux métier exige un alignement immédiat avec l’ERP).
     */
    private Map<TypeConge, EmployeeLeaveAllocation> loadAllocationsMappedToTypes(
            UserEntity user, int year, boolean refreshFromDolibarr) {
        if (refreshFromDolibarr && dolibarrService.isLeaveBalanceFromDolibarr(user)) {
            try {
                dolibarrService.refreshAllocationsForUser(user, year);
            } catch (RuntimeException ex) {
                log.warn("Sync Dolibarr solde ignorée pour user {} : {}", user.getId(), ex.getMessage());
            }
        }
        List<EmployeeLeaveAllocation> allocations =
                employeeLeaveAllocationRepository.findAllAllocationsForYear(user, year);
        return hrLeaveBalanceService.mapAllocationsToBusinessTypes(allocations);
    }

    private Optional<SoldeCongeResponse> soldeBlocPourType(List<SoldeCongeResponse> soldes, TypeConge type) {
        return soldes.stream().filter(s -> s.getTypeConge() == type).findFirst();
    }

    private void verifierSoldeDepuisAllocationDolibarr(UserEntity user, TypeConge type, int joursDemandes) {
        if (type == TypeConge.COURTE_DUREE && !countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            throw new IllegalArgumentException(
                    "Les sorties de courte durée ne sont pas disponibles pour votre pays.");
        }
        int year = Year.now().getValue();
        Map<TypeConge, EmployeeLeaveAllocation> byAppType =
                loadAllocationsMappedToTypes(user, year, false);
        EmployeeLeaveAllocation allocation = byAppType.get(type);
        if (allocation == null) {
            throw new IllegalStateException(
                    "Solde Dolibarr introuvable pour ce type de congé. Vérifiez les types harmonisés (CP, RTT, maladie) et la liaison fk_user Dolibarr.");
        }
        int disponibleLu = Math.max(0, safeDays(allocation.getJoursDisponibles()));
        int enAttenteCetteApplication =
                compterJoursDemandesEnAttenteSeulementPourType(user.getId(), type);
        int effectifPourNouvelleDemande = Math.max(0, disponibleLu - enAttenteCetteApplication);
        if (joursDemandes > effectifPourNouvelleDemande) {
            throw new IllegalStateException(String.format(
                    "Solde insuffisant (Dolibarr) : %d jour(s) demandé(s), %d jour(s) encore disponibles au regard du solde synchronisé et de vos autres demandes en attente dans l’application.",
                    joursDemandes,
                    Math.max(0, effectifPourNouvelleDemande)));
        }
    }

    private int compterJoursDemandesEnAttenteSeulementPourType(Long userId, TypeConge type) {
        List<JoursPrisParTypeProjection> rows = demandeCongeRepository.sumJoursPrisParTypePourUtilisateur(
                userId, EnumSet.of(StatutConge.EN_ATTENTE));
        return rows.stream()
                .filter(r -> r.getTypeConge() == type)
                .findFirst()
                .map(r -> r.getTotalJours().intValue())
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public DemandeCongeResponse getDemandeById(Long demandeId, Long currentUserId, Role role) {
        DemandeConge d = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        boolean rh = role == Role.RH || role == Role.ADMIN || role == Role.MANAGER;
        if (!rh && (d.getUser() == null || !d.getUser().getId().equals(currentUserId))) {
            throw new AccessDeniedException("Accès refusé à cette demande");
        }
        return toResponse(d);
    }

    @Transactional
    public DemandeCongeResponse validerDemande(
            Long demandeId,
            Long rhId,
            boolean accepte,
            String commentaire
    ) {
        UserEntity rh = userRepository.findById(rhId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur RH introuvable"));
        if (rh.getRole() != Role.RH && rh.getRole() != Role.MANAGER && rh.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Seul un validateur autorisé peut traiter une demande");
        }
        DemandeConge saved = workflowService.processDecision(
                demandeId,
                rh,
                accepte,
                StringUtils.hasText(commentaire) ? commentaire.trim() : null
        );
        log.info("Demande id={} {} par rhId={}", demandeId, accepte ? "acceptée" : "refusée", rhId);

        // Demande acceptée : on répercute les quantités vers Dolibarr (stockage ERP) — le débit métier est déjà calculé ici
        if (saved.getStatut() == StatutConge.ACCEPTE) {
            try {
                boolean synced = dolibarrService.syncApprovedLeave(saved);
                historyService.recordDolibarrSync(saved.getUser(), saved, synced ? "ALLOCATION_UPDATED" : "ALLOCATION_UPDATE_FAILED");
            } catch (Exception ex) {
                log.warn("Sync Dolibarr/RTT non appliquée pour demande id={} : {}", saved.getId(), ex.getMessage());
                historyService.recordDolibarrSync(saved.getUser(), saved, "ALLOCATION_UPDATE_FAILED");
            }
        }
        
        // Enregistrer la validation dans l'historique
        UserEntity employe = saved.getUser();
        if (accepte) {
            historyService.recordApproval(employe, saved, rh.getPrenom() + " " + rh.getNom());
        } else {
            historyService.recordRejection(employe, saved, commentaire != null ? commentaire : "Rejet sans motif spécifié");
        }

        String nomValidateur =
                "%s %s".formatted(truncateName(rh.getPrenom()), truncateName(rh.getNom())).trim();
        notifyEmployeurDecisionRh(employe, saved, rh, nomValidateur, commentaire);

        return toResponse(saved);
    }

    private static String truncateName(String part) {
        return part != null ? part.trim() : "";
    }

    private void notifyEmployeurDecisionRh(
            UserEntity employe,
            DemandeConge saved,
            UserEntity rh,
            String validateurNom,
            String commentaireBrut
    ) {
        if (employe == null || !StringUtils.hasText(employe.getEmail())) {
            return;
        }
        try {
            if (saved.getStatut() == StatutConge.ACCEPTE) {
                notificationService.notifyDemandeApproved(
                        employe, saved, StringUtils.hasText(validateurNom) ? validateurNom : rh.getEmail());
            } else if (saved.getStatut() == StatutConge.REFUSE) {
                String motif = StringUtils.hasText(saved.getCommentaireRh())
                        ? saved.getCommentaireRh().trim()
                        : (commentaireBrut != null ? commentaireBrut.trim() : "");
                if (!StringUtils.hasText(motif)) {
                    motif = "Aucun motif précisé.";
                }
                notificationService.notifyDemandeRejected(employe, saved, motif);
            }
        } catch (RuntimeException ex) {
            log.warn("SMTP : notification RH non envoyée pour la demande {} : {}", saved.getId(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<DemandeCongeResponse> getAllDemandesEnAttente() {
        return demandeCongeRepository.findByStatut(StatutConge.EN_ATTENTE).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StatistiquesRhResponse getStatistiquesRh() {
        Map<StatutConge, Long> nombreParStatut = new EnumMap<>(StatutConge.class);
        for (StatutConge statut : StatutConge.values()) {
            nombreParStatut.put(statut, demandeCongeRepository.countByStatut(statut));
        }
        return StatistiquesRhResponse.builder()
                .nombreParStatut(nombreParStatut)
                .build();
    }

    /** Stocke un fichier justificatif sur disque et met à jour le nom sur la demande. */
    @Transactional
    public Map<String, Object> saveAttachment(Long demandeId, Long userId, MultipartFile file) throws IOException {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        if (!demande.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Accès refusé");
        }
        String uploadsDir = System.getProperty("user.home") + "/conges-uploads/";
        new File(uploadsDir).mkdirs();
        String safe = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
        String filename = "demande_" + demandeId + "_" + System.currentTimeMillis() + "_" + safe;
        file.transferTo(new File(uploadsDir + filename));
        demande.setPieceJointeNom(filename);
        demandeCongeRepository.save(demande);
        return Map.of("message", "Pièce jointe enregistrée", "filename", filename);
    }

    private static int safeDays(Double value) {
        if (value == null || value.isNaN()) {
            return 0;
        }
        return (int) Math.round(value);
    }

    private int compterJoursPrisOuReservesPourType(Long userId, TypeConge type) {
        List<JoursPrisParTypeProjection> rows = demandeCongeRepository.sumJoursPrisParTypePourUtilisateur(
                userId,
                STATUTS_COMPTABILISES_SOLDE
        );
        return rows.stream()
                .filter(r -> r.getTypeConge() == type)
                .findFirst()
                .map(r -> r.getTotalJours().intValue())
                .orElse(0);
    }

    private DemandeCongeResponse toResponse(DemandeConge d) {
        UserEntity u = d.getUser();
        UserEntity appr = d.getApprovedBy();
        return DemandeCongeResponse.builder()
                .id(d.getId())
                .typeConge(d.getTypeConge())
                .dateDebut(d.getDateDebut())
                .dateFin(d.getDateFin())
                .nombreJours(d.getNombreJours())
                .nombreJoursExact(d.getNombreJoursExact())
                .startHalfDay(d.getStartHalfDay() == null ? null : d.getStartHalfDay().name())
                .endHalfDay(d.getEndHalfDay() == null ? null : d.getEndHalfDay().name())
                .statut(d.getStatut())
                .motif(d.getMotif())
                .commentaireRh(d.getCommentaireRh())
                .dateSoumission(d.getDateSoumission())
                .dateTraitement(d.getDateTraitement())
                .exceptionalLeaveConfigId(d.getExceptionalLeaveConfigId())
                .employe(DemandeCongeResponse.EmployeInfo.builder()
                        .nom(u.getNom())
                        .prenom(u.getPrenom())
                        .email(u.getEmail())
                        .build())
                .approuvePar(appr == null ? null : DemandeCongeResponse.ApproverInfo.builder()
                        .id(appr.getId())
                        .nom(appr.getNom())
                        .prenom(appr.getPrenom())
                        .email(appr.getEmail())
                        .build())
                .build();
    }
}
