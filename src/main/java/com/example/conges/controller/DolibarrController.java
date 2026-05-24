package com.example.conges.controller;

import com.example.conges.dto.dolibarr.DolibarrEmployeeDto;
import com.example.conges.dto.dolibarr.DolibarrHolidayDto;
import com.example.conges.dto.dolibarr.DolibarrLeaveAllocationDto;
import com.example.conges.dto.dolibarr.DolibarrLeaveTypeDto;
import com.example.conges.entity.UserEntity;
import com.example.conges.service.DolibarrApiClient;
import com.example.conges.service.DolibarrService;
import com.example.conges.service.DolibarrSyncService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur pour intégration Dolibarr
 * Endpoints pour synchroniser TOUTES les données depuis Dolibarr
 */
@RestController
@RequestMapping("/api/dolibarr")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('RH') or @environment.acceptsProfiles('dev')")
public class DolibarrController {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_MESSAGE = "message";

    private final DolibarrService dolibarrService;
    private final DolibarrSyncService dolibarrSyncService;
    private final DolibarrApiClient dolibarrApiClient;

    /**
     * GET /api/dolibarr/status
     * Teste la connexion à Dolibarr
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDolibarrStatus() {
        Map<String, Object> status = dolibarrApiClient.getStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * GET /api/dolibarr/users
     * Proxy backend -> Dolibarr /users
     */
    @GetMapping("/users")
    public ResponseEntity<Object> getUsers() {
        return ResponseEntity.ok(dolibarrApiClient.getUsers());
    }

    /**
     * GET /api/dolibarr/clients
     * Proxy backend -> Dolibarr /thirdparties
     */
    @GetMapping("/clients")
    public ResponseEntity<Object> getClients() {
        return ResponseEntity.ok(dolibarrApiClient.getThirdParties());
    }

    /**
     * GET /api/dolibarr/invoices
     * Proxy backend -> Dolibarr /invoices
     */
    @GetMapping("/invoices")
    public ResponseEntity<Object> getInvoices() {
        return ResponseEntity.ok(dolibarrApiClient.getInvoices());
    }

    /**
     * GET /api/dolibarr/employees
     * Récupère la liste des employés depuis Dolibarr
     */
    @GetMapping("/employees")
    public ResponseEntity<List<DolibarrEmployeeDto>> getDolibarrEmployees() {
        List<DolibarrEmployeeDto> employees = dolibarrService.getEmployeesFromDolibarr();
        return ResponseEntity.ok(employees);
    }

    /**
     * POST /api/dolibarr/sync
     * Synchronise les employés de Dolibarr vers la base locale
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncEmployees() {
        int syncCount = dolibarrService.syncEmployeesFromDolibarr();
        
        return ResponseEntity.ok(Map.of(
                KEY_SUCCESS, true,
                KEY_MESSAGE, "Synchronisation Dolibarr complétée",
                "employesSynced", syncCount
        ));
    }

    /**
     * POST /api/dolibarr/sync-by-email/{email}
     * Synchronise un employé spécifique par email
     */
    @PostMapping("/sync-by-email/{email}")
    public ResponseEntity<Map<String, Object>> syncEmployeeByEmail(@PathVariable String email) {
        UserEntity synced = dolibarrService.syncEmployeeByEmail(email);
        
        if (synced == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_MESSAGE, "Employé non trouvé sur Dolibarr: " + email
            ));
        }
        
        return ResponseEntity.ok(Map.of(
                KEY_SUCCESS, true,
                KEY_MESSAGE, "Employé synchronisé avec succès",
                "employee", Map.of(
                        "id", synced.getId(),
                        "email", synced.getEmail(),
                        "nom", synced.getNom(),
                        "prenom", synced.getPrenom(),
                        "dolibarrId", synced.getDolibarrId()
                )
        ));
    }

    /**
     * GET /api/dolibarr/test
     * Teste simplement la connexion à Dolibarr
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Boolean>> testConnection() {
        boolean connected = dolibarrService.testDolibarrConnection();
        return ResponseEntity.ok(Map.of("connected", connected));
    }

    /**
     * GET /api/dolibarr/db-holiday-users-count
     * Debug: vérifie la connexion DB Dolibarr (solde CP).
     */
    @GetMapping("/db-holiday-users-count")
    public ResponseEntity<Map<String, Object>> dolibarrDbHolidayUsersCount() {
        try {
            long count = dolibarrService.dolibarrDbHolidayUsersCount();
            return ResponseEntity.ok(Map.of(KEY_SUCCESS, true, "count", count));
        } catch (RuntimeException ex) {
            return ResponseEntity.ok(Map.of(KEY_SUCCESS, false, "error", ex.getMessage()));
        }
    }

    /**
     * POST /api/dolibarr/sync-all
     * Synchronise TOUTES les données : employés + types de congés + jours fériés + allocations
     * ENDPOINT PRINCIPAL - À UTILISER !
     */
    @PostMapping("/sync-all")
    public ResponseEntity<Object> syncAllDataFromDolibarr() {
        DolibarrSyncService.DolibarrFullSyncResponse response = 
                dolibarrSyncService.syncAllDataFromDolibarr();
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(Map.of(
                    KEY_SUCCESS, true,
                    KEY_MESSAGE, response.getMessage(),
                    "data", Map.of(
                            "employeesSynced", response.getEmployeesSynced(),
                            "leaveTypesSynced", response.getLeaveTypesSynced(),
                            "holidaysSynced", response.getHolidaysSynced(),
                            "allocationsSynced", response.getAllocationsSynced()
                    )
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    KEY_SUCCESS, false,
                    KEY_MESSAGE, response.getMessage()
            ));
        }
    }

    /**
     * GET /api/dolibarr/sync-all
     * Alias pratique (PowerShell appelle souvent en GET par défaut).
     */
    @GetMapping("/sync-all")
    public ResponseEntity<Object> syncAllDataFromDolibarrGet() {
        return syncAllDataFromDolibarr();
    }

    /**
     * GET /api/dolibarr/leave-types
     * Récupère tous les types de congés depuis Dolibarr
     */
    @GetMapping("/leave-types")
    public ResponseEntity<List<DolibarrLeaveTypeDto>> getLeaveTypes() {
        List<DolibarrLeaveTypeDto> leaveTypes = dolibarrService.getLeaveTypesFromDolibarr();
        if (leaveTypes == null || leaveTypes.isEmpty()) {
            try {
                leaveTypes = dolibarrService.getLeaveTypesFromDolibarrDb();
            } catch (RuntimeException ignored) {
                // keep empty: endpoint should never 500 for missing optional Dolibarr features
            }
        }
        return ResponseEntity.ok(leaveTypes);
    }

    /**
     * POST /api/dolibarr/sync-leave-types
     * Synchronise les types de congés de Dolibarr
     */
    @PostMapping("/sync-leave-types")
    public ResponseEntity<Map<String, Object>> syncLeaveTypes() {
        int syncCount = dolibarrSyncService.syncLeaveTypes();
        
        return ResponseEntity.ok(Map.of(
                KEY_SUCCESS, true,
                KEY_MESSAGE, "Types de congés synchronisés",
                "leaveTypesSynced", syncCount
        ));
    }

    /**
     * GET /api/dolibarr/holidays
     * Récupère tous les jours fériés depuis Dolibarr
     */
    @GetMapping("/holidays")
    public ResponseEntity<List<DolibarrHolidayDto>> getHolidays() {
        List<DolibarrHolidayDto> holidays = dolibarrService.getHolidaysFromDolibarr();
        return ResponseEntity.ok(holidays);
    }

    /**
     * POST /api/dolibarr/sync-holidays
     * Synchronise les jours fériés de Dolibarr
     */
    @PostMapping("/sync-holidays")
    public ResponseEntity<Map<String, Object>> syncHolidays() {
        int syncCount = dolibarrSyncService.syncHolidays();
        
        return ResponseEntity.ok(Map.of(
                KEY_SUCCESS, true,
                KEY_MESSAGE, "Jours fériés synchronisés",
                "holidaysSynced", syncCount
        ));
    }

    /**
     * GET /api/dolibarr/leave-allocations
     * Récupère toutes les allocations de congés depuis Dolibarr  
     */
    @GetMapping("/leave-allocations")
    public ResponseEntity<List<DolibarrLeaveAllocationDto>> getLeaveAllocations() {
        List<DolibarrLeaveAllocationDto> allocations = 
                dolibarrService.getLeaveAllocationsFromDolibarr();
        return ResponseEntity.ok(allocations);
    }

    /**
     * POST /api/dolibarr/sync-allocations
     * Synchronise les allocations de congés par employé
     */
    @PostMapping("/sync-allocations")
    public ResponseEntity<Map<String, Object>> syncAllocations() {
        int syncCount = dolibarrSyncService.syncLeaveAllocations();
        
        return ResponseEntity.ok(Map.of(
                KEY_SUCCESS, true,
                KEY_MESSAGE, "Allocations de congés synchronisées",
                "allocationsSynced", syncCount
        ));
    }
}
