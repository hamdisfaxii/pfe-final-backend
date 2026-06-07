package com.example.conges.controller;

import com.example.conges.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * 🔒 CORRIGÉ: Stats isolées par pays (Problème 4 Phase 2)
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/country")
    @PreAuthorize("hasAnyRole('RH', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> getCountryStats(
            @RequestParam String country) {
        Map<String, Object> stats = statsService.getStatsForCountry(country);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/global")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getGlobalStats() {
        Map<String, Object> stats = statsService.getGlobalStats();
        return ResponseEntity.ok(stats);
    }
}
