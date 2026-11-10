package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        Instant expiresAt,
        UUID userId,
        RolUsuario rol,
        String refreshToken,
        Instant refreshExpiresAt) {

    public AuthResponse(String accessToken, Instant expiresAt, UUID userId, RolUsuario rol) {
        this(accessToken, expiresAt, userId, rol, null, null);
    }
}
