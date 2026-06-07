package com.example.conges.service;

import com.example.conges.entity.StatutConge;
import com.example.conges.repository.DemandeCongeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

/**
 * 🔒 CORRIGÉ: Statistiques isolées par pays
 * Empêche une RH de voir les stats d'autres pays
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final DemandeCongeRepository demandeRepository;

    /**
     * Récupère les stats pour un pays donné
     */
    public Map<String, Object> getStatsForCountry(String country) {
        Map<String, Object> stats = new HashMap<>();

        // Compter les demandes par statut
        long pending = demandeRepository.countByStatutAndUser_PaysIgnoreCase(StatutConge.EN_ATTENTE, country);
        long approved = demandeRepository.countByStatutAndUser_PaysIgnoreCase(StatutConge.ACCEPTE, country);
        long rejected = demandeRepository.countByStatutAndUser_PaysIgnoreCase(StatutConge.REFUSE, country);

        stats.put("country", country);
        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("rejected", rejected);
        stats.put("total", pending + approved + rejected);

        return stats;
    }

    /**
     * Récupère les stats globales (ADMIN seulement)
     */
    public Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new HashMap<>();

        long totalPending = demandeRepository.countByStatut(StatutConge.EN_ATTENTE);
        long totalApproved = demandeRepository.countByStatut(StatutConge.ACCEPTE);
        long totalRejected = demandeRepository.countByStatut(StatutConge.REFUSE);

        stats.put("pending", totalPending);
        stats.put("approved", totalApproved);
        stats.put("rejected", totalRejected);
        stats.put("total", totalPending + totalApproved + totalRejected);

        return stats;
    }
}
