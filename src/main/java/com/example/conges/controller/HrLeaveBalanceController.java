package com.example.conges.controller;

import com.example.conges.dto.hr.HrLeaveBalanceDtos;
import com.example.conges.entity.Role;
import com.example.conges.service.HrLeaveBalanceService;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/balances")
@RequiredArgsConstructor
@PreAuthorize("hasRole('RH')")
public class HrLeaveBalanceController {

    private final HrLeaveBalanceService hrLeaveBalanceService;

    @GetMapping
    public ResponseEntity<HrLeaveBalanceDtos.PageResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(hrLeaveBalanceService.listBalances(q, country, role, year, page, size));
    }

    @PutMapping
    public ResponseEntity<?> update(@Valid @RequestBody List<HrLeaveBalanceDtos.UpdateRequest> body) {
        hrLeaveBalanceService.applyUpdates(body);
        return ResponseEntity.ok(java.util.Map.of("success", true));
    }
}


