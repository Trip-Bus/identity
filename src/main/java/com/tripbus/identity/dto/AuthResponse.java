package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(

        @Schema(description = "Access token JWT firmado en RS256; se valida con la llave pública "
                + "publicada en /.well-known/jwks.json",
                example = "eyJraWQiOiJpZGVudGl0eS1rZXktMSIsImFsZyI6IlJTMjU2In0...")
        String accessToken,

        @Schema(description = "Instante de expiración del access token", example = "2026-01-01T12:00:00Z")
        Instant expiresAt,

        @Schema(description = "Identificador del usuario autenticado", example = "18bd9885-32f1-4f6c-a889-0e6bfc18907d")
        UUID userId,

        @Schema(description = "Rol asignado al usuario", example = "PASAJERO")
        RolUsuario rol) {
}
