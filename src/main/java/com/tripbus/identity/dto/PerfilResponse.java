package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.time.Instant;
import java.util.UUID;

/** US-128 / US-130: perfil del usuario autenticado (GET y PUT /users/me). Nunca incluye passwordHash. */
public record PerfilResponse(
        UUID id,
        String email,
        boolean emailVerificado,
        String nombre,
        String telefono,
        String documentoIdentidad,
        RolUsuario rol,
        UUID companyId,
        Instant fechaCreacion) {
}
