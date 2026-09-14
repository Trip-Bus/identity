package com.tripbus.identity.security;

import com.tripbus.identity.model.RolUsuario;

import java.util.UUID;

public record UsuarioAutenticado(UUID userId, RolUsuario rol, UUID companyId) {
}
