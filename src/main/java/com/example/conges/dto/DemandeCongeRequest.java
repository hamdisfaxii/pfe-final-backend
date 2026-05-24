package com.example.conges.dto;

import com.example.conges.entity.TypeConge;
import com.fasterxml.jackson.annotation.JsonAlias;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import com.example.conges.entity.HalfDay;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemandeCongeRequest {

    // Accepte soit "Congé payé", "Congé sans solde", "Congé maladie" depuis le frontend
    @NotBlank(message = "Le titre du congé est obligatoire")
    private String titre;

    @NotNull(message = "La date de début est obligatoire")
    private LocalDate dateDebut;

    @NotNull(message = "La date de fin est obligatoire")
    private LocalDate dateFin;

    /** Texte libre ; le front envoie souvent {@code "motif"} au lieu de {@code "commentaire"}. */
    @JsonAlias({"motif"})
    private String commentaire;

    private LocalTime heureDebut;
    private LocalTime heureFin;

    /** Demi-journée début (matin/après-midi). */
    private HalfDay startHalfDay;

    /** Demi-journée fin (matin/après-midi). */
    private HalfDay endHalfDay;

    /** True uniquement depuis l’écran « permission / sortie courte » (sécurise l’API). */
    private Boolean demandeSortieCourte;

    /** Sélection d'un Super Admin (champ « Approuvé par »). */
    private Long approvedByAdminId;

    /** Congé exceptionnel: type configuré (par pays). */
    private Long exceptionalLeaveConfigId;

    public boolean isDemandeSortieCourte() {
        return Boolean.TRUE.equals(demandeSortieCourte);
    }

    // Getter pour obtenir le TypeConge basé sur le titre
    public TypeConge getTypeConge() {
        if (titre == null) {
            return TypeConge.PAYE;
        }
        String normalized = titre.toLowerCase().trim();
        if (normalized.contains("exceptionnel")) {
            return TypeConge.EXCEPTIONNEL;
        }
        if (normalized.contains("maladie")) {
            return TypeConge.MALADIE;
        }
        if (normalized.contains("sans solde")) {
            return TypeConge.SANS_SOLDE;
        }
        if (normalized.contains("courte") || normalized.contains("permission") || normalized.contains("sortie") || normalized.contains("rtt")) {
            return TypeConge.COURTE_DUREE;
        }
        if (normalized.contains("retard")) {
            return TypeConge.SANS_SOLDE;
        }
        return TypeConge.PAYE;
    }

    // Getter pour le motif (alias de commentaire)
    public String getMotif() {
        return commentaire != null ? commentaire : "";
    }
}
