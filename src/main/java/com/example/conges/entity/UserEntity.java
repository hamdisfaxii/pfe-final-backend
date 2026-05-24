package com.example.conges.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private Long dolibarrId;

    @Column(nullable = false, unique = true)
    private String email;

    private String nom;

    private String prenom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String pays;

    /** Code pays ISO métier facultatif ; sinon dérivé de {@link #pays}. */
    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "weekly_hours", precision = 5, scale = 2)
    private BigDecimal weeklyHours;

    @Column(name = "annual_work_days")
    private Integer annualWorkDays;

    @Column(name = "contract_type", length = 48)
    private String contractType;

    @Column(name = "contract_active", nullable = false)
    @Builder.Default
    private boolean contractActive = true;

    /** Date d'embauche (prorata congés payés / année). Optionnel ; sinon approximation via createdAt. */
    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "departement")
    private String departement;

    /** Mot de passe hashé BCrypt — utilisé uniquement quand Dolibarr n'est pas configuré. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
