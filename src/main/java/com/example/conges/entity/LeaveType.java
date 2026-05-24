package com.example.conges.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entité pour stocker les types de congés synchronisés depuis Dolibarr
 */
@Entity
@Table(name = "leave_types")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID Dolibarr — peut être null pour les types créés localement sans synchronisation. */
    @Column(unique = true)
    private Long dolibarrLeaveTypeId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String libelle;

    private String description;

    private String couleur;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Boolean requiresApproval;

    @Column(nullable = false)
    private Long delai;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    private Integer syncStatus = 1; // 1 = synced, 0 = needs update
}
