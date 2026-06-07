package com.example.conges.controller;

import com.example.conges.config.AppConstants;
import com.example.conges.dto.ai.DateSuggestionDto;
import com.example.conges.dto.ai.ConflictDetectionDto;
import com.example.conges.entity.TypeConge;
import com.example.conges.service.AIService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "AI Services", description = "Services IA pour suggestions intelligentes et détection de conflits")
public class AIController {

    private final AIService aiService;
    private final UserRepository userRepository;

    @GetMapping("/suggest-dates")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Suggère les meilleures dates pour un congé",
               description = "Utilise un algorithme intelligent pour proposer les 5 meilleures périodes")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Liste des suggestions de dates"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Accès refusé"),
        @ApiResponse(responseCode = "400", description = "Paramètres invalides")
    })
    public ResponseEntity<List<DateSuggestionDto>> suggestOptimalDates(
            @RequestParam TypeConge typeConge,
            @RequestParam(defaultValue = "5") @Parameter(description = "Nombre de jours demandés") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "Date de début (ISO format)") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "Date de fin (ISO format)") LocalDate endDate,
            Authentication authentication) {

        // Récupérer l'utilisateur authentifié (pas depuis le paramètre!)
        UserEntity currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppConstants.USER_NOT_FOUND));

        LocalDate start = startDate != null ? startDate : LocalDate.now();
        LocalDate end = endDate != null ? endDate : LocalDate.now().plusMonths(3);

        log.info("Recherche de dates optimales pour l'utilisateur {} : {} jours", currentUser.getId(), days);

        List<DateSuggestionDto> suggestions = aiService.suggestOptimalDates(
                currentUser.getId(),
                typeConge,
                days,
                start,
                end
        );

        return ResponseEntity.ok(suggestions);
    }

    @GetMapping("/detect-conflicts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Détecte les conflits d'absences potentiels",
               description = "Analyse les conflits simultanés, périodes critiques et charge RH")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Résultats de la détection de conflits"),
        @ApiResponse(responseCode = "401", description = "Non authentifié")
    })
    public ResponseEntity<ConflictDetectionDto> detectConflicts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam TypeConge typeConge,
            Authentication authentication) {

        // 🔒 Utiliser l'utilisateur authentifié (pas depuis le paramètre!)
        UserEntity currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppConstants.USER_NOT_FOUND));

        log.info("Détection de conflits pour l'utilisateur {} : {} à {}", currentUser.getId(), startDate, endDate);

        ConflictDetectionDto detection = aiService.detectConflicts(
                currentUser.getId(),
                startDate,
                endDate,
                typeConge
        );

        return ResponseEntity.ok(detection);
    }

    @GetMapping("/predict-trends")
    @PreAuthorize("hasAnyRole('RH', 'ADMIN')")
    @Operation(summary = "Prédit les tendances d'absences",
               description = "Analyse les tendances sur les 3 prochains mois et identifie les pics")
    @ApiResponse(responseCode = "200", description = "Prédictions des tendances")
    public ResponseEntity<Map<String, Object>> predictAbsenceTrends(
            @RequestParam String pays) {

        log.info("Prédiction des tendances d'absences pour le pays {}", pays);
        
        Map<String, Object> prediction = aiService.predictAbsenceTrends(pays);
        
        return ResponseEntity.ok(prediction);
    }

    @GetMapping("/analyze-balance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Analyse le solde de congés",
               description = "Fournit une analyse du solde actuel et des recommandations")
    @ApiResponse(responseCode = "200", description = "Analyse du solde")
    public ResponseEntity<Map<String, Object>> analyzeLeaveBalance(
            Authentication authentication) {

        // Récupérer l'utilisateur authentifié (pas depuis le paramètre!)
        UserEntity currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppConstants.USER_NOT_FOUND));

        log.info("Analyse du solde de congés pour l'utilisateur {}", currentUser.getId());

        Map<String, Object> analysis = aiService.analyzeLeaveBalance(currentUser.getId());

        return ResponseEntity.ok(analysis);
    }

    @GetMapping("/impact-score")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Évalue l'impact d'une demande de congés",
               description = "Fournit une analyse de l'impact équipe, continuité et recommandation")
    @ApiResponse(responseCode = "200", description = "Score d'impact avec recommandation")
    public ResponseEntity<Map<String, Object>> getImpactScore(
            @RequestParam Long demandeId,
            Authentication authentication) {

        UserEntity currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppConstants.USER_NOT_FOUND));

        log.info("Analyse impact pour demande {} par utilisateur {}", demandeId, currentUser.getId());

        Map<String, Object> impact = aiService.getImpactScore(demandeId);

        return ResponseEntity.ok(impact);
    }
}

