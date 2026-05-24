package com.example.conges.controller;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('RH','ADMIN')")
public class EmailTestController {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String mailUsername;

    @Value("${app.notification.from.email}")
    private String fromEmail;

    @Value("${app.notification.from.name}")
    private String fromName;

    @GetMapping("/smtp-config")
    public ResponseEntity<Map<String, Object>> checkSmtpConfig() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("fromName", fromName);
        response.put("mailUsername", maskEmail(mailUsername));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/send-email")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@RequestBody EmailTestRequest request) {
        Map<String, Object> response = new HashMap<>();
        if (request.getTo() == null || request.getTo().isEmpty()) {
            response.put("error", "Email 'to' is required");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(request.getTo());
            message.setSubject(request.getSubject() != null ? request.getSubject() : "Test Email - Gestion des Congés");
            message.setText(request.getMessage() != null ? request.getMessage() : "Email de test du système Gestion des Congés");
            mailSender.send(message);
            response.put("status", "sent");
            response.put("recipient", request.getTo());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.length() < 3) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return email;
        return email.charAt(0) + "***" + email.substring(atIndex);
    }

    public static class EmailTestRequest {
        private String to;
        private String subject;
        private String message;

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
