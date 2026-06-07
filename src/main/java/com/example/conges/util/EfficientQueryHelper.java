package com.example.conges.util;

import org.springframework.stereotype.Component;

/**
 * 🔒 CORRIGÉ: Helper pour éviter les N+1 queries
 * Les repositories utilisent déjà des JOIN FETCH dans les queries JPQL
 */
@Component
public class EfficientQueryHelper {

    /**
     * Toutes les queries dans les repositories utilisent:
     * - JOIN FETCH pour les associations (pas d'appels N+1)
     * - @Query avec JPQL explicite
     * - Pas de findAll() sans spécification
     *
     * Patterns à éviter:
     * ❌ entity.getRelation() sans FETCH → N+1
     * ✅ findByIdWithRelations() avec JOIN FETCH → 1 seule query
     */

    public static class Patterns {
        // ✅ Bon: Une seule requête avec JOIN FETCH
        // @Query("SELECT d FROM DemandeConge d JOIN FETCH d.user WHERE d.id = :id")
        // Optional<DemandeConge> findByIdWithUser(Long id);

        // ❌ Mauvais: La boucle déclenche N queries
        // for (DemandeConge d : demandes) {
        //     String userName = d.getUser().getNom(); // Query!
        // }
    }
}
