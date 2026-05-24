package com.example.conges.dto;

import com.example.conges.entity.Role;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private String token;
    @Builder.Default
    private String type = "Bearer";
    private UserInfo user;

    @Data
    @Builder
    public static class UserInfo {
        private Long id;
        private String email;
        private String nom;
        private String prenom;
        private Role role;
        private String pays;
        private String departement;
    }
}
