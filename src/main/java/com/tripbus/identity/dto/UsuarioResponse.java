package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.time.Instant;
import java.util.UUID;

/** Datos de un usuario hacia afuera: nunca incluye passwordHash. */
public record UsuarioResponse(
        UUID id,
        String email,
        String nombre,
        RolUsuario rol,
        UUID companyId,
        boolean activo,
        Instant fechaCreacion) {
}
