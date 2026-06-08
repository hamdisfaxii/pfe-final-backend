package com.example.conges.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    EMPLOYE("ROLE_EMPLOYE"),
    RH("ROLE_RH"),
    MANAGER("ROLE_MANAGER"),
    ADMIN("ROLE_ADMIN");

    private final String value;
}
