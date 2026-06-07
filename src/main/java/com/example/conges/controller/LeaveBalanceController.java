package com.example.conges.controller;

import com.example.conges.dto.LeaveBalanceDto;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;
import com.example.conges.service.LeaveBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 🔒 CORRIGÉ: Endpoint UNIQUE pour soldes
 * Backend est source de vérité - frontend affiche seulement
 */
@RestController
@RequestMapping("/api/leave-balance")
@RequiredArgsConstructor
public class LeaveBalanceController {

    private final LeaveBalanceService leaveBalanceService;
    private final UserRepository userRepository;

    @GetMapping("/{userId}/{leaveType}")
    public ResponseEntity<LeaveBalanceDto> getBalance(
        @PathVariable Long userId,
        @PathVariable String leaveType,
        Authentication authentication) {

        UserEntity currentUser = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // 🔒 Vérifier: utilisateur ne peut voir QUE ses soldes
        if (!currentUser.getId().equals(userId) && !"RH".equals(currentUser.getRole().toString())) {
            throw new AccessDeniedException("Accès refusé");
        }

        LeaveBalanceDto balance = leaveBalanceService.getBalance(userId, leaveType);
        return ResponseEntity.ok(balance);
    }
}
