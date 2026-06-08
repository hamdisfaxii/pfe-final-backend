package com.example.conges.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    EMPLOYE("ROLE_EMPLOYE"),
    RH("ROLE_RH");

    private final String value;
}
