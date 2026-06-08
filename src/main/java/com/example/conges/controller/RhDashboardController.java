package com.example.conges.controller;

import com.example.conges.dto.RhDashboardResponse;
import com.example.conges.service.RhDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rh")
@RequiredArgsConstructor
@PreAuthorize("hasRole('RH')")
public class RhDashboardController {

    private final RhDashboardService rhDashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<RhDashboardResponse> dashboard() {
        return ResponseEntity.ok(rhDashboardService.getDashboardComplet());
    }
}

