package com.example.conges.service;

import com.example.conges.dto.DemandeCongeResponse;
import com.example.conges.dto.hr.HrDecisionRequest;
import com.example.conges.dto.hr.HrLeaveRequestResponse;
import com.example.conges.entity.ApprovalDecision;
import com.example.conges.entity.DemandeApproval;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Role;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeApprovalRepository;
import com.example.conges.repository.DemandeCongeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HrDecisionService {

    private final DemandeCongeRepository demandeCongeRepository;
    private final DemandeApprovalRepository demandeApprovalRepository;
    private final CongeService congeService;
    private final CountryPolicyService countryPolicyService;

    @Transactional(readOnly = true)
    public List<HrLeaveRequestResponse> getPendingRequests(
            UserEntity actor,
            String employee,
            String country,
            String department,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateHrOrAdmin(actor);
        String effectiveCountry = resolveCountryFilter(actor, country);
        return demandeCongeRepository.findPendingForHrPanel(
                normalizeOptional(employee),
                effectiveCountry,
                normalizeOptional(department),
                startDate,
                endDate
        ).stream().map(this::toHrResponse).toList();
    }

    /**
     * Historique RH filtrable (tous statuts ou PENDING/APPROVED/REJECTED cÃ´tÃ© API).
     */
    @Transactional(readOnly = true)
    public List<HrLeaveRequestResponse> getHistoryRequests(
            UserEntity actor,
            String statusFilter,
            String employee,
            String country,
            String department,
            LocalDate startDate,
            LocalDate endDate
    ) {
        validateHrOrAdmin(actor);
        String effectiveCountry = resolveCountryFilter(actor, country);
        StatutConge statut = parseApiStatus(statusFilter);
        return demandeCongeRepository.findHistoryForHrPanel(
                statut,
                normalizeOptional(employee),
                effectiveCountry,
                normalizeOptional(department),
                startDate,
                endDate
        ).stream().map(this::toHrResponse).toList();
    }

    /** null = tous statuts ; accepte synonymes envoyÃ©s par le front React. */
    private StatutConge parseApiStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String u = status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(u)) {
            return null;
        }
        if ("PENDING".equals(u) || "EN_ATTENTE".equals(u)) {
            return StatutConge.EN_ATTENTE;
        }
        if ("APPROVED".equals(u) || "ACCEPTE".equals(u) || "ACCEPTED".equals(u)) {
            return StatutConge.ACCEPTE;
        }
        if ("REJECTED".equals(u) || "REFUSE".equals(u) || "REFUS".equals(u)) {
            return StatutConge.REFUSE;
        }
        throw new IllegalArgumentException("Statut filtre invalide: " + status);
    }

    @Transactional
    public DemandeCongeResponse processDecision(
            Long demandeId,
            UserEntity actor,
            HrDecisionRequest request
    ) {
        validateHrOrAdmin(actor);
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        enforceCountryAccess(actor, demande);
        boolean approve = parseDecision(request.getAction());
        String comment = request.getComment() == null ? null : request.getComment().trim();
        if (!approve && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("Le motif de rejet est obligatoire.");
        }
        return congeService.validerDemande(demandeId, actor.getId(), approve, comment);
    }

    @Transactional(readOnly = true)
    public HrLeaveRequestResponse getRequestDetails(Long demandeId, UserEntity actor) {
        validateHrOrAdmin(actor);
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        enforceCountryAccess(actor, demande);
        return toHrResponse(demande);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getStats(UserEntity actor) {
        validateHrOrAdmin(actor);
        String actorCountry = normalizeBusinessCountryOptional(actor.getPays());
        if (actor.getRole() != Role.RH && actorCountry == null) {
            throw new AccessDeniedException("Pays utilisateur introuvable.");
        }
        Map<String, Long> stats = new HashMap<>();
        long pending = actor.getRole() == Role.RH
                ? demandeCongeRepository.countByStatut(com.example.conges.entity.StatutConge.EN_ATTENTE)
                : demandeCongeRepository.countByStatutAndUser_PaysIgnoreCase(
                        com.example.conges.entity.StatutConge.EN_ATTENTE,
                        actorCountry
                );
        long approved = actor.getRole() == Role.RH
                ? demandeCongeRepository.countByStatut(com.example.conges.entity.StatutConge.ACCEPTE)
                : demandeCongeRepository.countByStatutAndUser_PaysIgnoreCase(
                        com.example.conges.entity.StatutConge.ACCEPTE,
                        actorCountry
                );
        long rejected = actor.getRole() == Role.RH
                ? demandeCongeRepository.countByStatut(com.example.conges.entity.StatutConge.REFUSE)
                : demandeCongeRepository.countByStatutAndUser_PaysIgnoreCase(
                        com.example.conges.entity.StatutConge.REFUSE,
                        actorCountry
                );
        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("rejected", rejected);
        stats.put("total", pending + approved + rejected);
        return stats;
    }

    private boolean parseDecision(String action) {
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if ("APPROVE".equals(normalized) || "APPROVED".equals(normalized)) {
            return true;
        }
        if ("REJECT".equals(normalized) || "REJECTED".equals(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("Action invalide. Utilisez APPROVE ou REJECT");
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void validateHrOrAdmin(UserEntity actor) {
        if (actor == null || actor.getRole() != Role.RH) {
            throw new AccessDeniedException("Accès réservé aux rôles RH");
        }
    }

    private String resolveCountryFilter(UserEntity actor, String requestedCountry) {
        if (actor.getRole() == Role.RH) {
            return normalizeOptional(requestedCountry);
        }
        String actorCountry = normalizeBusinessCountryOptional(actor.getPays());
        if (actorCountry == null) {
            throw new AccessDeniedException("Pays utilisateur introuvable.");
        }
        return actorCountry;
    }

    private void enforceCountryAccess(UserEntity actor, DemandeConge demande) {
        if (actor.getRole() == Role.RH) {
            return;
        }
        String actorCountry = normalizeBusinessCountryOptional(actor.getPays());
        String requestCountry =
                demande.getUser() == null ? null : normalizeBusinessCountryOptional(demande.getUser().getPays());
        if (actorCountry == null || requestCountry == null || !actorCountry.equalsIgnoreCase(requestCountry)) {
            throw new AccessDeniedException("AccÃ¨s refusÃ©: demande d'un autre pays.");
        }
    }

    private String normalizeBusinessCountryOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return countryPolicyService.normalizeBusinessCountry(value);
    }

    private DemandeApproval lastApprovedEntry(Long demandeId) {
        DemandeApproval last = null;
        for (DemandeApproval a : demandeApprovalRepository.findByDemandeIdOrderByStepOrderAscDecisionDateAsc(demandeId)) {
            if (a.getDecision() == ApprovalDecision.APPROVED) {
                last = a;
            }
        }
        return last;
    }

    private HrLeaveRequestResponse toHrResponse(DemandeConge d) {
        UserEntity u = d.getUser();
        UserEntity approver = d.getApprovedBy();
        LocalDateTime dateDecision = d.getDateTraitement();

        if (approver == null && d.getStatut() != StatutConge.EN_ATTENTE) {
            DemandeApproval fallback = lastApprovedEntry(d.getId());
            if (fallback != null) {
                approver = fallback.getActor();
                if (dateDecision == null) {
                    dateDecision = fallback.getDecisionDate();
                }
            }
        }
        return HrLeaveRequestResponse.builder()
                .id(d.getId())
                .typeConge(d.getTypeConge())
                .statut(d.getStatut())
                .dateDebut(d.getDateDebut())
                .dateFin(d.getDateFin())
                .nombreJours(d.getNombreJours())
                .nombreJoursExact(d.getNombreJoursExact())
                .startHalfDay(d.getStartHalfDay() == null ? null : d.getStartHalfDay().name())
                .endHalfDay(d.getEndHalfDay() == null ? null : d.getEndHalfDay().name())
                .motif(d.getMotif())
                .commentaireRh(d.getCommentaireRh())
                .dateSoumission(d.getDateSoumission())
                .dateDecision(dateDecision)
                .workflowCode(d.getWorkflowCode())
                .currentStepOrder(d.getCurrentStepOrder())
                .currentStepType(d.getCurrentStepType() == null ? null : d.getCurrentStepType().name())
                .employe(HrLeaveRequestResponse.EmployeInfo.builder()
                        .id(u.getId())
                        .nom(u.getNom())
                        .prenom(u.getPrenom())
                        .email(u.getEmail())
                        .country(u.getPays())
                        .department(u.getDepartement())
                        .build())
                .approuvePar(approver == null ? null : HrLeaveRequestResponse.ApproverInfo.builder()
                        .id(approver.getId())
                        .nom(approver.getNom())
                        .prenom(approver.getPrenom())
                        .email(approver.getEmail())
                        .build())
                .build();
    }
}

