package com.example.conges.dto.hr;

import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HrLeaveRequestResponse {
    private Long id;
    private TypeConge typeConge;
    private StatutConge statut;
    private LocalDate dateDebut;
    private LocalDate dateFin;
    private Integer nombreJours;
    private Double nombreJoursExact;
    private String startHalfDay;
    private String endHalfDay;
    private String motif;
    private String commentaireRh;
    private LocalDateTime dateSoumission;
    private LocalDateTime dateDecision;
    private String workflowCode;
    private Integer currentStepOrder;
    private String currentStepType;
    private EmployeInfo employe;
    private ApproverInfo approuvePar;

    @Data
    @Builder
    public static class EmployeInfo {
        private Long id;
        private String nom;
        private String prenom;
        private String email;
        private String country;
        private String department;
    }

    @Data
    @Builder
    public static class ApproverInfo {
        private Long id;
        private String nom;
        private String prenom;
        private String email;
    }
}
