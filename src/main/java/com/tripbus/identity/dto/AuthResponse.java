package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        Instant expiresAt,
        UUID userId,
        RolUsuario rol) {
}
