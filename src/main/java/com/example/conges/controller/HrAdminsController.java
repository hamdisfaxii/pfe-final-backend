package com.example.conges.controller;

import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/admins")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class HrAdminsController {

    private final UserRepository userRepository;

    /** GET /api/hr/admins — liste des administrateurs RH actifs. */
    @GetMapping
    public ResponseEntity<List<AdminOption>> list() {
        List<AdminOption> items = userRepository.findByRole(Role.ADMIN).stream()
                .filter(u -> u != null && u.isContractActive())
                .map(AdminOption::from)
                .toList();
        return ResponseEntity.ok(items);
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class AdminOption {
        private Long id;
        private String email;
        private String nom;
        private String prenom;

        static AdminOption from(UserEntity u) {
            return AdminOption.builder()
                    .id(u.getId()).email(u.getEmail())
                    .nom(u.getNom()).prenom(u.getPrenom())
                    .build();
        }
    }
}
