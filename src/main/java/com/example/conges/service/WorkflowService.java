package com.example.conges.service;

import com.example.conges.dto.workflow.WorkflowConfigRequest;
import com.example.conges.dto.workflow.WorkflowStepConfigRequest;
import com.example.conges.entity.ApprovalDecision;
import com.example.conges.entity.DemandeApproval;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Role;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.entity.WorkflowDefinition;
import com.example.conges.entity.WorkflowStep;
import com.example.conges.entity.WorkflowStepType;
import com.example.conges.repository.DemandeApprovalRepository;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.WorkflowDefinitionRepository;
import com.example.conges.repository.WorkflowStepRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final DemandeApprovalRepository demandeApprovalRepository;
    private final DemandeCongeRepository demandeCongeRepository;
    private final HourlyLeaveCapEvaluator hourlyLeaveCapEvaluator;

    @Transactional
    public void initializeWorkflow(DemandeConge demande) {
        WorkflowDefinition definition = resolveDefinitionForCountry(demande.getUser().getPays());
        List<WorkflowStep> steps = workflowStepRepository.findByWorkflowDefinitionIdOrderByStepOrderAsc(definition.getId());
        List<WorkflowStep> applicableSteps = getApplicableSteps(steps, demande);
        if (applicableSteps.isEmpty()) {
            throw new IllegalStateException("Aucune Ã©tape workflow active");
        }

        WorkflowStep firstStep = applicableSteps.get(0);
        demande.setWorkflowCode(definition.getCode());
        demande.setCurrentStepOrder(firstStep.getStepOrder());
        demande.setCurrentStepType(firstStep.getStepType());
        demande.setStatut(StatutConge.EN_ATTENTE);
    }

    @Transactional
    public DemandeConge processDecision(
            Long demandeId,
            UserEntity actor,
            boolean approved,
            String comment) {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        if (demande.getStatut() != StatutConge.EN_ATTENTE) {
            throw new IllegalStateException("La demande n'est plus en attente");
        }

        WorkflowDefinition definition = resolveDefinitionForDemande(demande);
        List<WorkflowStep> steps = workflowStepRepository.findByWorkflowDefinitionIdOrderByStepOrderAsc(definition.getId());
        List<WorkflowStep> applicableSteps = getApplicableSteps(steps, demande);

        WorkflowStep currentStep = applicableSteps.stream()
                .filter(step -> step.getStepOrder().equals(demande.getCurrentStepOrder()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ã‰tape courante workflow introuvable"));

        if (actor.getRole() != currentStep.getApproverRole()) {
            // ADMIN peut traiter toutes les Ã©tapes (dÃ©lÃ©gation / secours).
            if (actor.getRole() != Role.RH) {
                throw new AccessDeniedException("Votre rÃ´le ne peut pas traiter l'Ã©tape courante");
            }
        }

        demandeApprovalRepository.save(DemandeApproval.builder()
                .demande(demande)
                .stepOrder(currentStep.getStepOrder())
                .stepType(currentStep.getStepType())
                .actor(actor)
                .decision(approved ? ApprovalDecision.APPROVED : ApprovalDecision.REJECTED)
                .comment(comment)
                .build());

        if (!approved) {
            demande.setStatut(StatutConge.REFUSE);
            demande.setApprovedBy(actor);
            demande.setDateTraitement(LocalDateTime.now());
            if (comment != null && !comment.isBlank()) {
                demande.setCommentaireRh(comment.trim());
            }
            return demandeCongeRepository.save(demande);
        }

        WorkflowStep nextStep = applicableSteps.stream()
                .filter(step -> step.getStepOrder() > currentStep.getStepOrder())
                .min(Comparator.comparingInt(WorkflowStep::getStepOrder))
                .orElse(null);

        if (nextStep == null) {
            hourlyLeaveCapEvaluator.assertMonthlyCapOnAccept(demande, demande.getId());
            demande.setStatut(StatutConge.ACCEPTE);
            demande.setApprovedBy(actor);
            demande.setDateTraitement(LocalDateTime.now());
        } else {
            demande.setCurrentStepOrder(nextStep.getStepOrder());
            demande.setCurrentStepType(nextStep.getStepType());
        }

        return demandeCongeRepository.save(demande);
    }

    @Transactional(readOnly = true)
    public List<DemandeConge> getPendingForActorRole(Role role) {
        Set<WorkflowStepType> stepTypes = workflowStepRepository.findByApproverRole(role).stream()
                .map(WorkflowStep::getStepType)
                .collect(java.util.stream.Collectors.toSet());
        if (stepTypes.isEmpty()) {
            return List.of();
        }
        return demandeCongeRepository.findByStatutAndCurrentStepTypeIn(StatutConge.EN_ATTENTE, stepTypes);
    }

    @Transactional(readOnly = true)
    public List<DemandeApproval> getApprovals(Long demandeId) {
        return demandeApprovalRepository.findByDemandeIdOrderByStepOrderAscDecisionDateAsc(demandeId);
    }

    @Transactional
    protected WorkflowDefinition buildDefaultWorkflowDefinition() {
        WorkflowDefinition definition = workflowDefinitionRepository.findFirstByCountryCodeAndActiveTrue("DEFAULT")
                .orElseGet(() -> workflowDefinitionRepository.save(WorkflowDefinition.builder()
                        .code("WF_DEFAULT")
                        .countryCode("DEFAULT")
                        .active(Boolean.TRUE)
                        .build()));

        List<WorkflowStep> existing = workflowStepRepository.findByWorkflowDefinitionIdOrderByStepOrderAsc(definition.getId());
        if (existing.isEmpty()) {
            workflowStepRepository.save(WorkflowStep.builder()
                    .workflowDefinition(definition)
                    .stepOrder(1)
                    .stepType(com.example.conges.entity.WorkflowStepType.RH_APPROVAL)
                    .approverRole(Role.RH)
                    .required(Boolean.TRUE)
                    .minDays(1)
                    .build());
        }
        return definition;
    }

    @Transactional
    public WorkflowDefinition upsertWorkflow(WorkflowConfigRequest request) {
        String countryCode = request.getCountryCode().trim().toUpperCase(Locale.ROOT);
        WorkflowDefinition definition = workflowDefinitionRepository.findFirstByCountryCodeAndActiveTrue(countryCode)
                .orElseGet(() -> WorkflowDefinition.builder()
                        .countryCode(countryCode)
                        .active(Boolean.TRUE)
                        .build());
        definition.setCode(request.getCode());
        definition.setUpdatedAt(LocalDateTime.now());
        WorkflowDefinition savedDefinition = workflowDefinitionRepository.save(definition);

        List<WorkflowStep> currentSteps = workflowStepRepository.findByWorkflowDefinitionIdOrderByStepOrderAsc(savedDefinition.getId());
        if (!currentSteps.isEmpty()) {
            workflowStepRepository.deleteAll(currentSteps);
        }

        for (WorkflowStepConfigRequest step : request.getSteps()) {
            workflowStepRepository.save(WorkflowStep.builder()
                    .workflowDefinition(savedDefinition)
                    .stepOrder(step.getStepOrder())
                    .stepType(step.getStepType())
                    .approverRole(step.getApproverRole())
                    .required(Boolean.TRUE.equals(step.getRequired()))
                    .minDays(step.getMinDays())
                    .maxDays(step.getMaxDays())
                    .applicableLeaveTypes(step.getApplicableLeaveTypes())
                    .build());
        }
        return savedDefinition;
    }

    private WorkflowDefinition resolveDefinitionForCountry(String countryCode) {
        String normalizedCountry = normalizeCountry(countryCode);
        return workflowDefinitionRepository.findFirstByCountryCodeAndActiveTrue(normalizedCountry)
                .orElseGet(this::buildDefaultWorkflowDefinition);
    }

    private WorkflowDefinition resolveDefinitionForDemande(DemandeConge demande) {
        if (demande.getWorkflowCode() != null && !demande.getWorkflowCode().isBlank()) {
            return workflowDefinitionRepository.findFirstByCodeAndActiveTrue(demande.getWorkflowCode())
                    .orElseGet(() -> resolveDefinitionForCountry(demande.getUser().getPays()));
        }
        return resolveDefinitionForCountry(demande.getUser().getPays());
    }

    private String normalizeCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return "DEFAULT";
        }
        return countryCode.trim().toUpperCase(Locale.ROOT);
    }

    private List<WorkflowStep> getApplicableSteps(List<WorkflowStep> steps, DemandeConge demande) {
        List<WorkflowStep> applicable = steps.stream()
                .filter(step -> isApplicableToDemande(step, demande))
                .sorted(Comparator.comparingInt(WorkflowStep::getStepOrder))
                .toList();
        // Safe fallback to preserve behavior on old rows without conditions.
        return applicable.isEmpty()
                ? steps.stream().sorted(Comparator.comparingInt(WorkflowStep::getStepOrder)).toList()
                : applicable;
    }

    private boolean isApplicableToDemande(WorkflowStep step, DemandeConge demande) {
        int days = demande.getNombreJours();
        if (step.getMinDays() != null && days < step.getMinDays()) {
            return false;
        }
        if (step.getMaxDays() != null && days > step.getMaxDays()) {
            return false;
        }
        Set<TypeConge> scopedTypes = step.getApplicableLeaveTypes();
        return scopedTypes == null || scopedTypes.isEmpty() || scopedTypes.contains(demande.getTypeConge());
    }
}

