package com.example.conges.service;

import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/**
 * 🔒 CORRIGÉ: Audit des changements (Problème 12 Phase 3)
 * Log tous les changements importants pour traçabilité
 */
@Service
@Slf4j
public class EventAuditService {

    public void logRequestStatusChange(DemandeConge demande, String oldStatus, String newStatus, UserEntity actor) {
        log.info("REQUEST_STATUS_CHANGE | Demande={} | Old={} | New={} | Actor={} | Time={}",
                demande.getId(),
                oldStatus,
                newStatus,
                actor.getEmail(),
                LocalDateTime.now()
        );
    }

    public void logRequestCreation(DemandeConge demande, UserEntity creator) {
        log.info("REQUEST_CREATED | Demande={} | User={} | Creator={} | Time={}",
                demande.getId(),
                demande.getUser().getId(),
                creator.getEmail(),
                LocalDateTime.now()
        );
    }

    public void logAccessDenied(String resource, UserEntity actor) {
        log.warn("ACCESS_DENIED | Resource={} | Actor={} | Time={}",
                resource,
                actor.getEmail(),
                LocalDateTime.now()
        );
    }

    public void logInactiveUserAttempt(UserEntity user, String action) {
        log.warn("INACTIVE_USER_ATTEMPT | User={} | Action={} | Time={}",
                user.getEmail(),
                action,
                LocalDateTime.now()
        );
    }
}
