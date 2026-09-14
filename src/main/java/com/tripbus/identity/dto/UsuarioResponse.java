package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.util.UUID;

public record UsuarioResponse(
        UUID id,
        String email,
        String nombre,
        RolUsuario rol,
        UUID companyId,
        boolean activo) {
}
