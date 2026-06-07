package com.example.conges.service;

import com.example.conges.dto.LeaveBalanceDto;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.StatutConge;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.EmployeeLeaveAllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🔒 CORRIGÉ: Source de vérité UNIQUE pour les soldes
 * Centralise tous les calculs au backend pour éviter désync frontend/backend
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveBalanceService {

    private final EmployeeLeaveAllocationRepository allocationRepository;
    private final DemandeCongeRepository demandeRepository;

    public LeaveBalanceDto getBalance(Long userId, String leaveType) {
        log.info("Calcul soldes pour utilisateur {} type {}", userId, leaveType);

        EmployeeLeaveAllocation allocation = allocationRepository
            .findByUserIdAndLeaveTypeId(userId, leaveType)
            .orElseThrow(() -> new IllegalArgumentException("Allocation non trouvée"));

        List<DemandeConge> allDemands = demandeRepository
            .findByUserIdAndTypeConge(userId, leaveType);

        // Jours utilisés (acceptés)
        double daysUsed = allDemands.stream()
            .filter(d -> d.getStatut() == StatutConge.ACCEPTE)
            .mapToDouble(d -> d.getNombreJoursExact() != null
                ? d.getNombreJoursExact()
                : d.getNombreJours())
            .sum();

        // Jours en attente (réservés)
        double daysPending = allDemands.stream()
            .filter(d -> d.getStatut() == StatutConge.EN_ATTENTE)
            .mapToDouble(d -> d.getNombreJoursExact() != null
                ? d.getNombreJoursExact()
                : d.getNombreJours())
            .sum();

        double available = allocation.getJoursDisponibles() - daysUsed;
        double effectiveAvailable = available - daysPending;

        return LeaveBalanceDto.builder()
            .userId(userId)
            .leaveType(leaveType)
            .initialDays(allocation.getJoursDisponibles())
            .daysUsed(daysUsed)
            .daysPending(daysPending)
            .availableDays(available)
            .effectiveAvailable(effectiveAvailable)
            .calculatedAt(LocalDateTime.now())
            .build();
    }
}
