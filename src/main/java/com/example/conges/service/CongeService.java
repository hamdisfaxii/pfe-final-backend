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
 * Cycle de vie des demandes de congÃ© et exposition des soldes Ã  lâ€™UI.
 * <p>Compose les rÃ¨gles pays ({@link CountryPolicyService}), le cas RTT France ({@link FranceRttLedgerService}) et les
 * allocations synchronisÃ©es depuis Dolibarr. Dolibarr stocke des quantitÃ©s synchronisÃ©es ; les calculs mÃ©tier par pays
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
                    "Ce type de demande doit Ãªtre crÃ©Ã© depuis l'Ã©cran Â« Autorisation courte Â».");
        }

        if (request.getDateFin().isBefore(request.getDateDebut())) {
            throw new IllegalArgumentException("La date de fin doit Ãªtre aprÃ¨s ou Ã©gale Ã  la date de dÃ©but");
        }

        // Blocage chevauchements (demandes en attente ou acceptÃ©es).
        long overlaps = demandeCongeRepository.countOverlappingDemandes(
                userId,
                EnumSet.of(StatutConge.EN_ATTENTE, StatutConge.ACCEPTE),
                request.getDateDebut(),
                request.getDateFin()
        );
        if (overlaps > 0) {
            throw new IllegalStateException("Chevauchement dÃ©tectÃ© : vous avez dÃ©jÃ  une demande sur cette pÃ©riode.");
        }

        if (type == TypeConge.COURTE_DUREE && fr && !countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            throw new IllegalArgumentException("Les autorisations courtes sont dÃ©sactivÃ©es pour votre pays.");
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
                            "Une autorisation courte hors France doit porter sur une seule journÃ©e.");
                }
                hourlyLeaveCapEvaluator.assertMonthlyCapForNewRequest(
                        userId, request.getDateDebut(), user.getPays());
                int mins = DemandeConge.minutesBetween(hd, hf);
                if (mins != CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES) {
                    throw new IllegalArgumentException(
                            "DurÃ©e invalide : %d minutes (exactement %d minutes attendues)."
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
                // France : 2 variantes sur le mÃªme type:
                // - RTT (jour / demi-journÃ©e): calc jours ouvrÃ©s, solde RTT.
                // - Autorisation courte 2h (comme TN/MA): horaires + cap mensuel.
                boolean hasHours = hd != null && hf != null;
                if (hasHours) {
                    hrWorkScheduleService.validatePermissionWithinWorkingHours(user, request);
                    if (!request.getDateDebut().equals(request.getDateFin())) {
                        throw new IllegalArgumentException(
                                "Une autorisation courte (2 h) doit porter sur une seule journÃ©e.");
                    }
                    hourlyLeaveCapEvaluator.assertMonthlyCapForNewRequest(
                            userId, request.getDateDebut(), user.getPays());
                    int mins = DemandeConge.minutesBetween(hd, hf);
                    if (mins != CountryPolicyService.NON_FR_SHORT_LEAVE_MINUTES) {
                        throw new IllegalArgumentException(
                                "DurÃ©e invalide : %d minutes (exactement %d minutes attendues)."
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
                        throw new IllegalArgumentException("Aucun jour ouvrable dans la pÃ©riode choisie");
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
                throw new IllegalArgumentException("Aucun jour ouvrable dans la pÃ©riode choisie");
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

        // Champ Â« ApprouvÃ© par Â» : pour compatibilitÃ©, auto-assign si absent.
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
        log.info("Demande de congÃ© crÃ©Ã©e id={} pour userId={}", saved.getId(), userId);

        historyService.recordCreation(user, saved);

        // Email de confirmation Ã  l'employÃ©
        notificationService.notifyDemandeCreated(user, saved);

        // Email aux Super Admins Ã  la crÃ©ation (anti-doublon via History)
        notifySuperAdminsOnCreate(user, saved, approvedBy);

        // Email Ã  l'approbateur dÃ©signÃ© dans Â« ApprouvÃ© par Â»
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
            log.warn("SMTP : notification approbateur non envoyÃ©e pour la demande {} : {}", demande.getId(), ex.getMessage());
        }
    }

    private DemandeConge buildExceptionalLeaveDemande(UserEntity user, DemandeCongeRequest request, String paysNorm) {
        Long cfgId = request.getExceptionalLeaveConfigId();
        if (cfgId == null) {
            throw new IllegalArgumentException("Veuillez sÃ©lectionner un congÃ© exceptionnel.");
        }
        ExceptionalLeaveConfig cfg = exceptionalLeaveConfigRepository.findById(cfgId)
                .orElseThrow(() -> new IllegalArgumentException("CongÃ© exceptionnel introuvable."));
        String cfgCountry = countryPolicyService.normalizeBusinessCountry(cfg.getCountryCode());
        if (!cfgCountry.equalsIgnoreCase(paysNorm)) {
            throw new IllegalArgumentException("CongÃ© exceptionnel invalide pour votre pays.");
        }
        if (!Boolean.TRUE.equals(cfg.getEnabled())) {
            throw new IllegalArgumentException("Ce congÃ© exceptionnel est dÃ©sactivÃ©.");
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
            throw new IllegalArgumentException("Aucun jour ouvrable dans la pÃ©riode choisie");
        }
        // Quota annuel par type exceptionnel (EN_ATTENTE + ACCEPTE).
        int year = request.getDateDebut().getYear();
        double usedOrPending = demandeCongeRepository.sumExceptionalDaysForUserAndConfig(
                user.getId(), cfgId, year, EnumSet.of(StatutConge.EN_ATTENTE, StatutConge.ACCEPTE));
        double quota = cfg.getDaysPerYear() == null ? 0d : Math.max(0d, cfg.getDaysPerYear());
        double remaining = Math.max(0d, quota - usedOrPending);
        if (joursExact - remaining > 1e-9) {
            throw new IllegalStateException(String.format(
                    "Solde congÃ© exceptionnel insuffisant (%s) : %.2f j. demandÃ©(s), %.2f disponible(s) sur %.2f.",
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
            if (u.getRole() != Role.RH) {
                throw new IllegalArgumentException("Le champ Â« ApprouvÃ© par Â» doit Ãªtre un Super Admin.");
            }
            return u;
        }
        List<UserEntity> admins = userRepository.findByRole(Role.RH);
        if (admins == null || admins.isEmpty()) {
            return null;
        }
        return admins.get(0);
    }

    private void notifySuperAdminsOnCreate(UserEntity requester, DemandeConge demande, UserEntity approvedBy) {
        try {
            // L'approbateur reÃ§oit dÃ©jÃ  notifyPendingApproval â€” on l'exclut ici pour Ã©viter le doublon.
            String approverEmail = (approvedBy != null && approvedBy.getEmail() != null)
                    ? approvedBy.getEmail().trim().toLowerCase() : null;
            List<UserEntity> admins = userRepository.findByRole(Role.RH);
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
            log.warn("SMTP : notification Super Admins non envoyÃ©e pour la demande {} : {}", demande.getId(), ex.getMessage());
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
            throw new IllegalStateException("Seules les demandes en attente peuvent Ãªtre annulÃ©es");
        }

        UserEntity user = demande.getUser();
        demande.setStatut(StatutConge.ANNULE);
        demande.setDateTraitement(LocalDateTime.now());
        DemandeConge saved = demandeCongeRepository.save(demande);
        
        log.info("Demande id={} annulÃ©e par userId={}", demandeId, userId);
        if (saved.getDolibarrLeaveRequestId() != null) {
            log.warn("Demande id={} annulÃ©e mais non synchronisÃ©e dans Dolibarr (dolibarrId={}). Ã€ annuler manuellement dans Dolibarr si besoin.",
                    demandeId, saved.getDolibarrLeaveRequestId());
        }

        // Enregistrer l'annulation dans l'historique
        historyService.recordCancellation(user, saved, "Demande annulÃ©e par l'employÃ©");
        
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
     * Liste des demandes pour lâ€™historique employÃ© avec filtres (annÃ©e civile intersectÃ©e ; statut).
     * Les paramÃ¨tres {@code statutRaw} correspondent aux valeurs envoyÃ©es par le frontend (accordÃ©e, attenteâ€¦).
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
     * @return vide si aucun filtre ({@code tous} ou blanc), sinon statut mÃ©tier correspondant au libellÃ© API.
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
                // Fallback: si pas d'allocation existante, renvoyer 0 (ou quota pays si tu prÃ©fÃ¨res)
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
     * RÃ©ponse enrichie compatible frontend (soldes CP / courte durÃ©e / maladie, etc.).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSoldeResponseMap(Long userId) {
        UserEntity principal = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));
        List<SoldeCongeResponse> soldes = calculerSolde(userId);
        return buildSoldeApiMap(principal, soldes);
    }

    /**
     * Horaires actifs du pays mÃ©tier de lâ€™employÃ© (mÃªme source que la validation des permissions courtes).
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
             * 0 jour = valeur Dolibarr (pas un calcul automatique pays dans lâ€™app). Souvent : pas de ligne dâ€™allocation
             * pour un type reliÃ© comme Â« maladie Â», pas de fk_user Dolibarr, ou solde vraiment Ã  0.
             */
            messageMaladie =
                    mal <= 0
                            ? "CongÃ© maladie : valeur lue depuis Dolibarr uniquement. Si elle reste Ã  0 : vÃ©rifiez quâ€™un type congÃ© maladie existe, quâ€™il est synchronisÃ©, et que lâ€™employÃ© a bien une allocation (nb jours disponibles)."
                            : "";
        } else {
            malQuota = countryPolicyService.getAnnualQuota(user.getPays(), TypeConge.MALADIE);
            maladieNonDecompte = malQuota <= 0;
            int prisMal = compterJoursPrisOuReservesPourType(user.getId(), TypeConge.MALADIE);
            mal = maladieNonDecompte ? 0 : Math.max(0, malQuota - prisMal);
            messageMaladie =
                    maladieNonDecompte
                            ? "CongÃ© maladie : suivi hors quota dÃ©comptÃ© dans lâ€™application (consultez vos rÃ¨gles RH)."
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
     * Exclut le samedi et le dimanche. Les jours fÃ©riÃ©s ne sont pas pris en compte pour l'instant
     * (Ã©volution prÃ©vue avec la gestion multi-pays).
     */
    public int calculerJoursOuvrables(LocalDate debut, LocalDate fin) {
        return DemandeConge.calculerJoursOuvrables(debut, fin);
    }

/**
 * VÃ©rifie que l'utilisateur dispose d'assez de jours pour le type de congÃ© demandÃ©.
 * <p>Si Dolibarr est liÃ© ({@link DolibarrService#isLeaveBalanceFromDolibarr}) : le plafond contrÃ´lÃ© est le solde
 * disponible lu depuis les allocations synchronisÃ©es (valeurs stockÃ©es cÃ´tÃ© Dolibarr), diminuÃ© des demandes
 * {@link StatutConge#EN_ATTENTE} dans cette app ; Dolibarr nâ€™applique pas les rÃ¨gles pays â€” il fournit les quantitÃ©s.</p>
 * <p>Sinon : plafonds pays calculÃ©s ici ({@link CountryPolicyService}) avec prise en compte des demandes
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
            throw new IllegalArgumentException("Le nombre de jours demandÃ©s doit Ãªtre strictement positif");
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
         * Lorsque lâ€™utilisateur est liÃ© Ã  Dolibarr et que lâ€™API est configurÃ©e, le solde utilisÃ© pour
         * accepter une demande est celui lu depuis les allocations Dolibarr (avec retrait des autres
         * demandes dÃ©jÃ  Â« en attente Â» dans cette appli mais pas encore reflÃ©tÃ©es cÃ´tÃ© Dolibarr).
         * La mise Ã  jour dÃ©finitive des soldes se fait ensuite dans Dolibarr Ã  lâ€™approbation.
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
                            "Solde de congÃ©s payÃ©s insuffisant : %.2f jour(s) ouvrable(s) demandÃ©(s), %.2f disponible(s) sur %d (prorata pays)",
                            joursDemandesExact,
                            Math.max(0d, restants),
                            ceiling));
                }
            }
            case COURTE_DUREE -> {
                if (!countryPolicyService.isRttEnabledForCountry(user.getPays())) {
                    throw new IllegalArgumentException(
                            "Les sorties de courte durÃ©e ne sont pas disponibles pour votre pays.");
                }
                int prisOuReserve = compterJoursPrisOuReservesPourType(userId, TypeConge.COURTE_DUREE);
                int quota = countryPolicyService.getAnnualRttQuota(user.getPays());
                double restants = quota - prisOuReserve;
                if (joursDemandesExact - restants > 1e-9) {
                    throw new IllegalStateException(String.format(
                            "Solde sortie courte durÃ©e insuffisant (plafond %d j.) : %.2f j. demandÃ©(s), %.2f disponible(s)",
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
                            "Plafond annuel congÃ© maladie (%d j.) : %.2f jour(s) demandÃ©(s), %.2f disponible(s)",
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
                    "Les sorties de courte durÃ©e ne sont pas disponibles pour votre pays.");
        }
        int year = Year.now().getValue();
        Map<TypeConge, EmployeeLeaveAllocation> byAppType =
                loadAllocationsMappedToTypes(user, year, false);
        EmployeeLeaveAllocation allocation = byAppType.get(type);
        if (allocation == null) {
            throw new IllegalStateException(
                    "Solde Dolibarr introuvable pour ce type de congÃ©. VÃ©rifiez les types harmonisÃ©s (CP, RTT, maladie) et la liaison fk_user Dolibarr.");
        }
        double disponibleLu = Math.max(0d, safeDays(allocation.getJoursDisponibles()));
        double enAttenteCetteApplication =
                compterJoursDemandesEnAttenteSeulementPourTypeExact(user.getId(), type);
        double effectifPourNouvelleDemande = Math.max(0d, disponibleLu - enAttenteCetteApplication);
        if (joursDemandesExact - effectifPourNouvelleDemande > 1e-9) {
            throw new IllegalStateException(String.format(
                    "Solde insuffisant (Dolibarr) : %.2f jour(s) demandÃ©(s), %.2f jour(s) encore disponibles au regard du solde synchronisÃ© et de vos autres demandes en attente dans lâ€™application.",
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
     * Lit les allocations locales pour lâ€™annÃ©e et applique la mÃªme grille mÃ©tier que la page RH Soldes.
     * <p><b>Ne tire pas Dolibarr Ã  chaque appel</b> : sinon chaque GET {@code /conge/solde} rÃ©Ã©craserait la base locale
     * avec les valeurs distantes et annulerait les corrections RH ou les montants dÃ©jÃ  persistÃ©s ici. La synchro
     * Dolibarr se fait au login, via {@code sync-all}, ou aprÃ¨s flux mÃ©tier explicitement.</p>
     *
     * @param refreshFromDolibarr si {@code true}, tente {@link DolibarrService#refreshAllocationsForUser} avant lecture
     *                            (rÃ©servÃ© aux cas oÃ¹ un flux mÃ©tier exige un alignement immÃ©diat avec lâ€™ERP).
     */
    private Map<TypeConge, EmployeeLeaveAllocation> loadAllocationsMappedToTypes(
            UserEntity user, int year, boolean refreshFromDolibarr) {
        if (refreshFromDolibarr && dolibarrService.isLeaveBalanceFromDolibarr(user)) {
            try {
                dolibarrService.refreshAllocationsForUser(user, year);
            } catch (RuntimeException ex) {
                log.warn("Sync Dolibarr solde ignorÃ©e pour user {} : {}", user.getId(), ex.getMessage());
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
                    "Les sorties de courte durÃ©e ne sont pas disponibles pour votre pays.");
        }
        int year = Year.now().getValue();
        Map<TypeConge, EmployeeLeaveAllocation> byAppType =
                loadAllocationsMappedToTypes(user, year, false);
        EmployeeLeaveAllocation allocation = byAppType.get(type);
        if (allocation == null) {
            throw new IllegalStateException(
                    "Solde Dolibarr introuvable pour ce type de congÃ©. VÃ©rifiez les types harmonisÃ©s (CP, RTT, maladie) et la liaison fk_user Dolibarr.");
        }
        int disponibleLu = Math.max(0, safeDays(allocation.getJoursDisponibles()));
        int enAttenteCetteApplication =
                compterJoursDemandesEnAttenteSeulementPourType(user.getId(), type);
        int effectifPourNouvelleDemande = Math.max(0, disponibleLu - enAttenteCetteApplication);
        if (joursDemandes > effectifPourNouvelleDemande) {
            throw new IllegalStateException(String.format(
                    "Solde insuffisant (Dolibarr) : %d jour(s) demandÃ©(s), %d jour(s) encore disponibles au regard du solde synchronisÃ© et de vos autres demandes en attente dans lâ€™application.",
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
        boolean rh = role == Role.RH;
        if (!rh && (d.getUser() == null || !d.getUser().getId().equals(currentUserId))) {
            throw new AccessDeniedException("AccÃ¨s refusÃ© Ã  cette demande");
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
        if (rh.getRole() != Role.RH) {
            throw new AccessDeniedException("Seul un validateur autorisÃ© peut traiter une demande");
        }
        DemandeConge saved = workflowService.processDecision(
                demandeId,
                rh,
                accepte,
                StringUtils.hasText(commentaire) ? commentaire.trim() : null
        );
        log.info("Demande id={} {} par rhId={}", demandeId, accepte ? "acceptÃ©e" : "refusÃ©e", rhId);

        // Demande acceptÃ©e : on rÃ©percute les quantitÃ©s vers Dolibarr (stockage ERP) â€” le dÃ©bit mÃ©tier est dÃ©jÃ  calculÃ© ici
        if (saved.getStatut() == StatutConge.ACCEPTE) {
            try {
                boolean synced = dolibarrService.syncApprovedLeave(saved);
                historyService.recordDolibarrSync(saved.getUser(), saved, synced ? "ALLOCATION_UPDATED" : "ALLOCATION_UPDATE_FAILED");
            } catch (Exception ex) {
                log.warn("Sync Dolibarr/RTT non appliquÃ©e pour demande id={} : {}", saved.getId(), ex.getMessage());
                historyService.recordDolibarrSync(saved.getUser(), saved, "ALLOCATION_UPDATE_FAILED");
            }
        }
        
        // Enregistrer la validation dans l'historique
        UserEntity employe = saved.getUser();
        if (accepte) {
            historyService.recordApproval(employe, saved, rh.getPrenom() + " " + rh.getNom());
        } else {
            historyService.recordRejection(employe, saved, commentaire != null ? commentaire : "Rejet sans motif spÃ©cifiÃ©");
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
                    motif = "Aucun motif prÃ©cisÃ©.";
                }
                notificationService.notifyDemandeRejected(employe, saved, motif);
            }
        } catch (RuntimeException ex) {
            log.warn("SMTP : notification RH non envoyÃ©e pour la demande {} : {}", saved.getId(), ex.getMessage());
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

    /** Stocke un fichier justificatif sur disque et met Ã  jour le nom sur la demande. */
    @Transactional
    public Map<String, Object> saveAttachment(Long demandeId, Long userId, MultipartFile file) throws IOException {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        if (!demande.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("AccÃ¨s refusÃ©");
        }
        String uploadsDir = System.getProperty("user.home") + "/conges-uploads/";
        new File(uploadsDir).mkdirs();
        String safe = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
        String filename = "demande_" + demandeId + "_" + System.currentTimeMillis() + "_" + safe;
        file.transferTo(new File(uploadsDir + filename));
        demande.setPieceJointeNom(filename);
        demandeCongeRepository.save(demande);
        return Map.of("message", "PiÃ¨ce jointe enregistrÃ©e", "filename", filename);
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

