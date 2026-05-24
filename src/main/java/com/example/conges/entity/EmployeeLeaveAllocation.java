package com.example.conges.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entité pour stocker les allocations de congés par employé
 * Synchronisée depuis Dolibarr
 */
@Entity
@Table(name = "employee_leave_allocations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeLeaveAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    /** ID allocation Dolibarr — peut être null pour les allocations créées manuellement par le RH. */
    @Column(unique = true)
    private Long dolibarrAllocationId;

    @Column(nullable = false)
    private Double joursInitiaux;

    @Column(nullable = false)
    private Double joursUtilises;

    @Column(nullable = false)
    private Double joursDisponibles;

    @Column(nullable = false)
    private Integer annee;

    @Column(nullable = false)
    private LocalDate dateDebut;

    @Column(nullable = false)
    private LocalDate dateFin;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Double getJoursRestants() {
        return joursDisponibles;
    }

    public void updateFromDolibarr(Double joursUtilises, Double joursDisponibles) {
        this.joursUtilises = joursUtilises;
        this.joursDisponibles = joursDisponibles;
        this.updatedAt = LocalDateTime.now();
    }
}
