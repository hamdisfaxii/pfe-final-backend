package com.example.conges.controller;

import com.example.conges.dto.workflow.WorkflowConfigRequest;
import com.example.conges.entity.DemandeApproval;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.entity.WorkflowDefinition;
import com.example.conges.service.WorkflowService;
import java.util.List;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping("/definitions")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<WorkflowDefinition> upsertWorkflow(@Valid @RequestBody WorkflowConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowService.upsertWorkflow(request));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<List<DemandeConge>> pendingForRole(@AuthenticationPrincipal UserEntity user) {
        Role role = user.getRole();
        return ResponseEntity.ok(workflowService.getPendingForActorRole(role));
    }

    @GetMapping("/demandes/{demandeId}/approvals")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<List<DemandeApproval>> getApprovals(@PathVariable Long demandeId) {
        return ResponseEntity.ok(workflowService.getApprovals(demandeId));
    }
}

