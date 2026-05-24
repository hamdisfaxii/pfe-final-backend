package com.example.conges.controller;

import com.example.conges.dto.hr.HrDecisionRequest;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;
import com.example.conges.service.HrDecisionService;
import com.example.conges.service.JwtService;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Slf4j
public class PublicDecisionController {

    private final JwtService jwtService;
    private final HrDecisionService hrDecisionService;
    private final UserRepository userRepository;

    @Value("${app.url:http://localhost:5175}")
    private String appUrl;

    @GetMapping("/decision")
    public ResponseEntity<Void> processEmailDecision(@RequestParam String token) {
        Map<String, Object> claims = jwtService.validateApprovalToken(token);
        if (claims == null) {
            return redirect(appUrl + "/rh/decisions");
        }

        Long demandeId = (Long) claims.get("demandeId");
        Long rhId = (Long) claims.get("rhId");
        String action = (String) claims.get("action");

        UserEntity rh = userRepository.findById(rhId).orElse(null);
        if (rh == null) {
            return redirect(appUrl + "/rh/decisions");
        }

        try {
            HrDecisionRequest request = new HrDecisionRequest();
            request.setAction(action);
            request.setComment("REJECT".equals(action) ? "Rejeté depuis l'email" : "");
            hrDecisionService.processDecision(demandeId, rh, request);
        } catch (Exception e) {
            log.warn("Décision email échouée demandeId={} action={}: {}", demandeId, action, e.getMessage());
        }

        return redirect(appUrl + "/rh/requests/" + demandeId);
    }

    private ResponseEntity<Void> redirect(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(url));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
