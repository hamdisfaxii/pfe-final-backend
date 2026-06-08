package com.example.conges.controller;

import com.example.conges.dto.DemandeCongeResponse;
import com.example.conges.dto.hr.HrDecisionRequest;
import com.example.conges.dto.hr.HrLeaveRequestResponse;
import com.example.conges.entity.UserEntity;
import com.example.conges.service.HrDecisionService;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({ "/api/rh/requests", "/api/hr/requests" })
@RequiredArgsConstructor
@PreAuthorize("hasRole('RH')")
public class HrDecisionController {

    private final HrDecisionService hrDecisionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal UserEntity actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String employee,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        List<HrLeaveRequestResponse> requests = hrDecisionService.getHistoryRequests(
                actor,
                status,
                employee,
                country,
                department,
                startDate,
                endDate
        );
        Map<String, Object> body = new HashMap<>();
        body.put("requests", requests);
        body.put("total", requests.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/pending")
    public ResponseEntity<List<HrLeaveRequestResponse>> getPending(
            @AuthenticationPrincipal UserEntity actor,
            @RequestParam(required = false) String employee,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(hrDecisionService.getPendingRequests(
                actor,
                employee,
                country,
                department,
                startDate,
                endDate
        ));
    }

    @PostMapping("/{demandeId}/decision")
    public ResponseEntity<DemandeCongeResponse> decide(
            @PathVariable Long demandeId,
            @AuthenticationPrincipal UserEntity actor,
            @Valid @RequestBody HrDecisionRequest request
    ) {
        return ResponseEntity.ok(hrDecisionService.processDecision(demandeId, actor, request));
    }

    @GetMapping("/{demandeId}")
    public ResponseEntity<HrLeaveRequestResponse> getDetails(
            @PathVariable Long demandeId,
            @AuthenticationPrincipal UserEntity actor
    ) {
        return ResponseEntity.ok(hrDecisionService.getRequestDetails(demandeId, actor));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> stats(@AuthenticationPrincipal UserEntity actor) {
        return ResponseEntity.ok(hrDecisionService.getStats(actor));
    }
}

