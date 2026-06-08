package com.example.conges.controller;

import com.example.conges.entity.DemandeAttachment;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeAttachmentRepository;
import com.example.conges.repository.DemandeCongeRepository;
import java.util.List;
import java.util.Map;
import javax.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/conge")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DemandeAttachmentController {

    private final DemandeCongeRepository demandeCongeRepository;
    private final DemandeAttachmentRepository demandeAttachmentRepository;

    @GetMapping("/{demandeId}/attachments")
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable Long demandeId,
            @AuthenticationPrincipal UserEntity actor
    ) {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        assertMayAccess(actor, demande);
        List<Map<String, Object>> out = demandeAttachmentRepository.findByDemande_IdOrderByUploadedAtDesc(demandeId)
                .stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.getId(),
                        "filename", a.getFilename(),
                        "contentType", a.getContentType(),
                        "sizeBytes", a.getSizeBytes(),
                        "uploadedAt", a.getUploadedAt()
                ))
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping(path = "/{demandeId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable Long demandeId,
            @AuthenticationPrincipal UserEntity actor,
            @RequestParam("file") MultipartFile file
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Fichier obligatoire.");
        }
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new EntityNotFoundException("Demande introuvable"));
        assertMayAccess(actor, demande);

        DemandeAttachment saved = demandeAttachmentRepository.save(DemandeAttachment.builder()
                .demande(demande)
                .filename(String.valueOf(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .content(file.getBytes())
                .build());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "id", saved.getId()
        ));
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(
            @PathVariable Long attachmentId,
            @AuthenticationPrincipal UserEntity actor
    ) {
        DemandeAttachment a = demandeAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new EntityNotFoundException("Fichier introuvable"));
        DemandeConge demande = a.getDemande();
        assertMayAccess(actor, demande);

        String ct = a.getContentType();
        MediaType mt = (ct == null || ct.isBlank()) ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(ct);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + a.getFilename() + "\"")
                .contentType(mt)
                .body(a.getContent());
    }

    private void assertMayAccess(UserEntity actor, DemandeConge demande) {
        if (actor == null) throw new AccessDeniedException("Authentification requise");
        Role r = actor.getRole();
        boolean staff = r == Role.RH;
        if (staff) return;
        if (demande == null || demande.getUser() == null || demande.getUser().getId() == null) {
            throw new AccessDeniedException("AccÃ¨s refusÃ©");
        }
        if (!demande.getUser().getId().equals(actor.getId())) {
            throw new AccessDeniedException("AccÃ¨s refusÃ©");
        }
    }
}


