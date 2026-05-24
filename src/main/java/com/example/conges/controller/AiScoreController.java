package com.example.conges.controller;

import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.entity.StatutConge;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demande")
@RequiredArgsConstructor
public class AiScoreController {

    private final DemandeCongeRepository demandeCongeRepository;

    @PostMapping("/ai-score")
    public ResponseEntity<Map<String, Object>> calculateAiScore(
            @AuthenticationPrincipal UserEntity user,
            @RequestBody Map<String, Object> body) {

        if (user == null) {
            return ResponseEntity.status(401).build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> demandeData = body.get("demandeData") instanceof Map
                ? (Map<String, Object>) body.get("demandeData") : new HashMap<>();

        int score = computeScore(user.getId(), demandeData);
        String riskLevel = score < 35 ? "FAIBLE" : score < 65 ? "MOYEN" : "ÉLEVÉ";

        List<String> impactFactors = buildImpactFactors(demandeData);
        List<String> riskFactors   = buildRiskFactors(user.getId(), demandeData);

        String message = switch (riskLevel) {
            case "FAIBLE"  -> "Demande conforme, approbation recommandée.";
            case "MOYEN"   -> "Demande acceptable, vérification conseillée.";
            default        -> "Demande nécessite une attention particulière.";
        };
        int confidence = score < 35 ? 92 : score < 65 ? 75 : 60;

        Map<String, Object> aiScore = new HashMap<>();
        aiScore.put("score", score);
        aiScore.put("riskLevel", riskLevel);
        aiScore.put("impactFactors", impactFactors);
        aiScore.put("riskFactors", riskFactors);
        aiScore.put("recommendation", Map.of("message", message, "confidence", confidence));

        return ResponseEntity.ok(Map.of("success", true, "aiScore", aiScore));
    }

    private int computeScore(Long userId, Map<String, Object> d) {
        int score = 20; // base

        // Durée de la demande
        double jours = toDouble(d.get("nombreJours"));
        if (jours <= 0 && d.get("dateDebut") != null && d.get("dateFin") != null) {
            jours = daysBetween(String.valueOf(d.get("dateDebut")), String.valueOf(d.get("dateFin")));
        }
        if (jours >= 15) score += 40;
        else if (jours >= 8) score += 25;
        else if (jours >= 4) score += 12;
        else score += 5;

        // Préavis : moins de 3 jours = risque élevé
        String dateDebut = d.get("dateDebut") != null ? String.valueOf(d.get("dateDebut")) : null;
        if (dateDebut != null) {
            long preavis = daysBetween(LocalDate.now().toString(), dateDebut);
            if (preavis < 0)  score += 30;
            else if (preavis < 3)  score += 20;
            else if (preavis < 7)  score += 8;
        }

        // Fréquence des demandes récentes (3 derniers mois)
        long recentCount = demandeCongeRepository.countRecentByUser(
                userId, LocalDate.now().minusMonths(3).atStartOfDay(), StatutConge.REFUSE);
        if (recentCount >= 3) score += 15;
        else if (recentCount == 2) score += 8;

        return Math.min(100, Math.max(0, score));
    }

    private List<String> buildImpactFactors(Map<String, Object> d) {
        List<String> factors = new ArrayList<>();
        double jours = toDouble(d.get("nombreJours"));
        if (jours > 0) factors.add(String.format("Durée : %.0f jour(s)", jours));
        Object type = d.get("typeConge");
        if (type != null) factors.add("Type : " + type);
        return factors;
    }

    private List<String> buildRiskFactors(Long userId, Map<String, Object> d) {
        List<String> risks = new ArrayList<>();

        String dateDebut = d.get("dateDebut") != null ? String.valueOf(d.get("dateDebut")) : null;
        if (dateDebut != null) {
            long preavis = daysBetween(LocalDate.now().toString(), dateDebut);
            if (preavis < 3) risks.add("Préavis court (< 3 jours)");
        }

        double jours = toDouble(d.get("nombreJours"));
        if (jours >= 10) risks.add("Absence longue durée");

        long recentCount = demandeCongeRepository.countRecentByUser(
                userId, LocalDate.now().minusMonths(3).atStartOfDay(), StatutConge.REFUSE);
        if (recentCount >= 3) risks.add("Fréquence élevée (3+ demandes récentes)");

        return risks;
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private long daysBetween(String from, String to) {
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
