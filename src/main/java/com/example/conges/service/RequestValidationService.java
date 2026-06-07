package com.example.conges.service;

import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

/**
 * 🔒 CORRIGÉ: Validation centralisée des demandes
 * Gère les race conditions et les validations métier
 */
@Service
@RequiredArgsConstructor
public class RequestValidationService {

    private final DemandeCongeRepository demandeRepository;

    public void validateRequestCanBeCreated(UserEntity user, LocalDate start, LocalDate end) {
        // Vérifier inactivité utilisateur (Problème 11)
        if (!user.isContractActive()) {
            throw new IllegalStateException("Utilisateur inactif - impossible de créer une demande");
        }

        // Vérifier chevauchement (Problème 9)
        long overlapping = demandeRepository.countOverlappingDemandes(
                user.getId(),
                java.util.Arrays.asList(
                        com.example.conges.entity.StatutConge.ACCEPTE,
                        com.example.conges.entity.StatutConge.EN_ATTENTE
                ),
                start,
                end
        );
        if (overlapping > 0) {
            throw new IllegalStateException("Période déjà couverte par une autre demande");
        }
    }

    public void validateRequestTransition(DemandeConge demande) {
        // Race condition: vérifier que le statut n'a pas changé (Problème 10)
        String currentStatus = demande.getStatut().toString();

        DemandeConge fresh = demandeRepository.findById(demande.getId())
                .orElseThrow(() -> new IllegalStateException("Demande non trouvée"));

        if (!fresh.getStatut().toString().equals(currentStatus)) {
            throw new IllegalStateException("La demande a déjà été modifiée. Rechargez et réessayez.");
        }
    }
}
