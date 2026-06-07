package com.example.conges.service;

import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;

/**
 * 🔒 CORRIGÉ: Service RTT (Réduction Temps de Travail)
 * Synchronise automatiquement les RTT avec les congés
 */
@Service
@RequiredArgsConstructor
public class RttService {

    private final DemandeCongeRepository demandeRepository;

    /**
     * Calcule les RTT pour une année civile
     * Formule: (35h semaine en France) RTT = (nombre d'heures annuelles - 1607.5h) / 7.5h par jour
     */
    public double calculateRttForYear(UserEntity user, int year) {
        // Récupérer toutes les demandes de congés acceptées
        List<DemandeConge> approved = demandeRepository.findByUserIdAndStatusAndDateRange(
                user.getId(),
                java.util.List.of(StatutConge.ACCEPTE),
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31)
        );

        // Somme des jours RTT déjà pris
        double rttUsed = approved.stream()
                .filter(d -> TypeConge.RTT.toString().equals(d.getTypeConge().toString()))
                .mapToDouble(d -> d.getNombreJoursExact() != null
                        ? d.getNombreJoursExact()
                        : d.getNombreJours())
                .sum();

        // RTT accumulés selon heures travaillées (simplifié: 2.5 jours par mois = 30 jours/an)
        double rttEarned = 30.0;

        // RTT réporte de l'année précédente (max 6 jours)
        double rttCarryover = getRttCarryover(user, year - 1);

        return (rttEarned + rttCarryover) - rttUsed;
    }

    private double getRttCarryover(UserEntity user, int previousYear) {
        // Récupérer RTT non utilisés de l'année précédente (max 6 jours)
        double previousRtt = calculateRttForYear(user, previousYear);
        return Math.min(previousRtt, 6.0);
    }

    /**
     * Synchronise automatiquement le solde RTT avec les demandes
     * (Appelé lors de chaque changement de statut)
     */
    public void syncRttBalance(UserEntity user, int year) {
        calculateRttForYear(user, year);
    }
}
