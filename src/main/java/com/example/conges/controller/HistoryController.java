package com.example.conges.controller;

import com.example.conges.dto.HistoryResponse;
import com.example.conges.entity.History;
import com.example.conges.entity.History.ActionType;
import com.example.conges.mapper.HistoryMapper;
import com.example.conges.service.HistoryService;
import com.example.conges.service.export.ExcelExportService;
import com.example.conges.service.export.PdfExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Controller REST pour la gestion de l'historique des actions
 * Endpoints pour consultation, filtrage et export des donnÃ©es.
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
@Slf4j
public class HistoryController {

    private final HistoryService historyService;
    private final PdfExportService pdfExportService;
    private final ExcelExportService excelExportService;
    private final HistoryMapper historyMapper;

    /**
     * GET /api/history
     * RÃ©cupÃ¨re l'historique avec filtres et pagination
     * 
     * Params optionnels:
     * - userId: filtrer par utilisateur
     * - demandeId: filtrer par demande de congÃ©
     * - actionType: filtrer par type d'action
     * - pays: filtrer par pays
     * - startDate: date de dÃ©but (ISO format)
     * - endDate: date de fin (ISO format)
     * - page: numÃ©ro de page (dÃ©faut 0)
     * - size: taille de page (dÃ©faut 20)
     * - sort: tri (dÃ©faut actionDate,desc)
     */
    @GetMapping
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<Page<HistoryResponse>> getHistory(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long demandeId,
            @RequestParam(required = false) ActionType actionType,
            @RequestParam(required = false) String pays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "actionDate,desc") String[] sort) {
        
        Sort.Order sortOrder = Sort.Order.desc("actionDate");
        if (sort.length > 0 && sort[0].contains(",")) {
            String[] parts = sort[0].split(",");
            sortOrder = new Sort.Order(
                    "asc".equalsIgnoreCase(parts[1]) ? Sort.Direction.ASC : Sort.Direction.DESC,
                    parts[0]
            );
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(sortOrder));
        Page<History> result = historyService.getHistory(userId, demandeId, actionType, pays, startDate, endDate, pageable);
        Page<HistoryResponse> response = result.map(historyMapper::toResponse);
        
        log.info("Historique consultÃ© - {} records trouvÃ©s", result.getTotalElements());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/history/user/:userId
     * RÃ©cupÃ¨re l'historique d'un utilisateur spÃ©cifique
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasAnyRole('RH', 'ADMIN') or #userId == principal.id")
    public ResponseEntity<Page<HistoryResponse>> getUserHistory(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "actionDate"));
        Page<History> result = historyService.getUserHistory(userId, pageable);
        Page<HistoryResponse> response = result.map(historyMapper::toResponse);
        
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/history/demande/:demandeId
     * RÃ©cupÃ¨re l'historique d'une demande de congÃ© spÃ©cifique
     */
    @GetMapping("/demande/{demandeId}")
    @PreAuthorize("hasAnyRole('RH', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<Page<HistoryResponse>> getDemandeHistory(
            @PathVariable Long demandeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "actionDate"));
        Page<History> result = historyService.getDemandeHistory(demandeId, pageable);
        Page<HistoryResponse> response = result.map(historyMapper::toResponse);
        
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/history/statistics
     * RÃ©cupÃ¨re les statistiques sur les actions enregistrÃ©es
     */
    @GetMapping("/statistics")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<Map<ActionType, Long>> getStatistics() {
        Map<ActionType, Long> stats = historyService.getActionStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/history/export/pdf
     * Exporte l'historique au format PDF
     * 
     * Query params: mÃªmes que /api/history pour le filtrage
     */
    @GetMapping("/export/pdf")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<byte[]> exportHistoryPdf(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long demandeId,
            @RequestParam(required = false) ActionType actionType,
            @RequestParam(required = false) String pays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            List<History> historyList = historyService.getHistoryForExport(
                    userId, demandeId, actionType, pays, startDate, endDate
            );

            byte[] pdfBytes = pdfExportService.generateHistoryReport(
                    historyList,
                    "Rapport Historique - Gestion des CongÃ©s"
            );

            log.info("Rapport PDF gÃ©nÃ©rÃ© - {} records", historyList.size());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"historique_" + System.currentTimeMillis() + ".pdf\"")
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .body(pdfBytes);

        } catch (IOException e) {
            log.error("Erreur lors de la gÃ©nÃ©ration du PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/history/export/excel
     * Exporte l'historique au format Excel
     * 
     * Query params: mÃªmes que /api/history pour le filtrage
     */
    @GetMapping("/export/excel")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<byte[]> exportHistoryExcel(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long demandeId,
            @RequestParam(required = false) ActionType actionType,
            @RequestParam(required = false) String pays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            List<History> historyList = historyService.getHistoryForExport(
                    userId, demandeId, actionType, pays, startDate, endDate
            );

            byte[] excelBytes = excelExportService.generateHistoryExcel(
                    historyList,
                    "Historique"
            );

            log.info("Rapport Excel gÃ©nÃ©rÃ© - {} records", historyList.size());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"historique_" + System.currentTimeMillis() + ".xlsx\"")
                    .header(HttpHeaders.CONTENT_TYPE,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excelBytes);

        } catch (IOException e) {
            log.error("Erreur lors de la gÃ©nÃ©ration du Excel", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * GET /api/history/export/rh-report
     * Exporte un rapport RH spÃ©cifique (Excel)
     */
    @GetMapping("/export/rh-report")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<byte[]> exportRhReport(
            @RequestParam(required = false) String pays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        try {
            List<History> historyList = historyService.getHistoryForExport(
                    null, null, null, pays, startDate, endDate
            );

            byte[] excelBytes = excelExportService.generateRhReport(historyList);

            log.info("Rapport RH gÃ©nÃ©rÃ© - {} records", historyList.size());

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"rapport_rh_" + System.currentTimeMillis() + ".xlsx\"")
                    .header(HttpHeaders.CONTENT_TYPE,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(excelBytes);

        } catch (IOException e) {
            log.error("Erreur lors de la gÃ©nÃ©ration du rapport RH", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

