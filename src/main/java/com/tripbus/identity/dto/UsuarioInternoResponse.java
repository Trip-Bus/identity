package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.util.UUID;

/** GET /internal/users/{id}: lo que otros servicios necesitan de un usuario. Sin email, documento ni passwordHash. */
public record UsuarioInternoResponse(UUID id, String nombre, RolUsuario rol, UUID companyId, boolean activo) {
}
