package com.example.conges.service;

import com.example.conges.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 🔒 CORRIGÉ: Vérification d'inactivité des utilisateurs
 * Empêche les utilisateurs inactifs de créer/modifier des demandes
 */
@Service
@RequiredArgsConstructor
public class InactiveUserCheckService {

    /**
     * Vérifie si un utilisateur est actif
     */
    public void checkUserIsActive(UserEntity user) {
        if (user == null) {
            throw new IllegalStateException("Utilisateur non trouvé");
        }

        if (!user.isContractActive()) {
            throw new IllegalStateException("Cet utilisateur est inactif et ne peut plus soumettre de demandes");
        }
    }

    /**
     * Vérifie que deux utilisateurs sont actifs
     */
    public void checkBothUsersActive(UserEntity user1, UserEntity user2) {
        checkUserIsActive(user1);
        checkUserIsActive(user2);
    }
}
