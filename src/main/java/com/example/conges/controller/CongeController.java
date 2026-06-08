package com.example.conges.controller;

import com.example.conges.dto.DemandeCongeRequest;
import com.example.conges.dto.DemandeCongeResponse;
import com.example.conges.dto.StatistiquesRhResponse;
import com.example.conges.dto.config.WorkScheduleConfigResponse;
import com.example.conges.entity.Role;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.service.CongeService;
import javax.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Point d'entrée REST pour les opérations de congé de l'employé connecté.
 * Mappé sur /api/conge pour compatibilité avec le frontend.
 */
@RestController
@RequestMapping("/api/conge")
@RequiredArgsConstructor
public class CongeController {

    private final CongeService congeService;

    /** GET /api/conge/solde — solde enrichi (CP, RTT, maladie…). */
    @GetMapping("/solde")
    public ResponseEntity<Map<String, Object>> getSolde(@AuthenticationPrincipal UserEntity user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(congeService.getSoldeResponseMap(user.getId()));
    }

    /** GET /api/conge/meta/work-schedule — horaires de travail du pays de l'employé. */
    @GetMapping("/meta/work-schedule")
    public ResponseEntity<WorkScheduleConfigResponse> getMyWorkSchedule(
            @AuthenticationPrincipal UserEntity user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(congeService.getActiveWorkScheduleForUser(user.getId()));
    }

    /** GET /api/conge/liste — liste des demandes de l'employé, filtrable par année et statut. */
    @GetMapping("/liste")
    public ResponseEntity<Map<String, Object>> getListe(
            @AuthenticationPrincipal UserEntity user,
            @RequestParam(required = false) Integer annee,
            @RequestParam(required = false) String statut) {

        boolean isRh = user.getRole() == Role.RH;
        List<DemandeCongeResponse> demandes = isRh
                ? congeService.getAllDemandesEnAttente()
                : congeService.getMesDemandesFiltrees(user.getId(), annee, statut);

        Map<String, Object> response = new HashMap<>();
        response.put("demandes", demandes);
        response.put("data", demandes);
        return ResponseEntity.ok(response);
    }

    /** GET /api/conge/{id} — détail d'une demande. */
    @GetMapping("/{id}")
    public ResponseEntity<DemandeCongeResponse> getDemandeById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserEntity user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(congeService.getDemandeById(id, user.getId(), user.getRole()));
    }

    /** POST /api/conge — soumet une nouvelle demande de congé. */
    @PostMapping
    public ResponseEntity<DemandeCongeResponse> creerDemande(
            @AuthenticationPrincipal UserEntity user,
            @Valid @RequestBody DemandeCongeRequest request) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(congeService.creerDemande(user.getId(), request));
    }

    /** PUT /api/conge/{id}/valider — validation RH d'une demande. */
    @PutMapping("/{id}/valider")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<DemandeCongeResponse> validerDemande(
            @PathVariable Long id,
            @AuthenticationPrincipal UserEntity rh,
            @RequestBody Map<String, Object> body) {
        boolean accepte = Boolean.TRUE.equals(body.get("accepte"));
        String commentaire = body.getOrDefault("commentaire", "").toString();
        return ResponseEntity.ok(congeService.validerDemande(id, rh.getId(), accepte, commentaire));
    }

    /**
     * PUT /api/conge/{id}/annuler — annule une demande de congé.
     * DELETE /api/conge/{id}     — alias supporté par le frontend.
     */
    @PutMapping("/{id}/annuler")
    @DeleteMapping("/{id}")
    public ResponseEntity<DemandeCongeResponse> annulerDemande(
            @PathVariable Long id,
            @AuthenticationPrincipal UserEntity user) {
        return ResponseEntity.ok(congeService.annulerDemande(id, user.getId()));
    }

    /** POST /api/conge/{id}/attachment — pièce jointe (justificatif) pour une demande. */
    @PostMapping("/{id}/attachment")
    public ResponseEntity<Map<String, Object>> uploadAttachment(
            @PathVariable Long id,
            @AuthenticationPrincipal UserEntity user,
            @RequestParam("pieceJointe") MultipartFile file) throws IOException {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(congeService.saveAttachment(id, user.getId(), file));
    }

    /** GET /api/conge/stats — statistiques RH (réservé RH/Admin). */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('RH')")
    public ResponseEntity<Map<String, Object>> getStats() {
        StatistiquesRhResponse stats = congeService.getStatistiquesRh();
        Map<StatutConge, Long> parStatut = stats.getNombreParStatut();

        Map<String, Object> result = new HashMap<>();
        result.put("pending",  parStatut.getOrDefault(StatutConge.EN_ATTENTE, 0L));
        result.put("approved", parStatut.getOrDefault(StatutConge.ACCEPTE, 0L));
        result.put("rejected", parStatut.getOrDefault(StatutConge.REFUSE, 0L));
        result.put("total",    parStatut.values().stream().mapToLong(Long::longValue).sum());
        return ResponseEntity.ok(result);
    }
}
