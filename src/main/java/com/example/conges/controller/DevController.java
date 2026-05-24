package com.example.conges.controller;

import com.example.conges.dto.AuthResponse;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;
import com.example.conges.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 🔧 Dev-only controller for testing and local development.
 * Disabled in production profiles.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
@Slf4j
@RequiredArgsConstructor
public class DevController {

    private final UserRepository userRepository;
    private final JwtService jwtService;

    /**
     * Generate a test JWT token without Dolibarr authentication.
     * ⚠️ DEV ONLY - Do not use in production!
     */
    @GetMapping("/get-test-token")
    public ResponseEntity<AuthResponse> getTestToken(
            @RequestParam(defaultValue = "admin@company.com") String email,
            @RequestParam(defaultValue = "RH") String role
    ) {
        log.warn("🔧 [DEV MODE] Generating test token for email={}, role={}", email, role);

        UserEntity user = userRepository.findByEmail(email)
                .orElseGet(() -> {
                    UserEntity newUser = UserEntity.builder()
                            .email(email)
                            .nom("Test")
                            .prenom(role.equals("RH") ? "RH" : role.equals("ADMIN") ? "Admin" : "Employee")
                            .role(Role.valueOf(role))
                            .pays("TN")
                            .annualWorkDays(20)
                            .weeklyHours(new java.math.BigDecimal("40.0"))
                            .contractActive(true)
                            .contractType("CDI")
                            .build();
                    return userRepository.save(newUser);
                });

        String token = jwtService.generateToken(user);
        log.info("✅ [DEV] Test token generated for {}", email);

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .nom(user.getNom())
                        .prenom(user.getPrenom())
                        .role(user.getRole())
                        .pays(user.getPays())
                        .departement(user.getDepartement())
                        .build())
                .build());
    }

    /**
     * Health check endpoint for dev environment
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("✅ Dev backend is running");
    }

}
