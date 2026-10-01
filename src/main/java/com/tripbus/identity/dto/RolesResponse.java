package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.util.List;
import java.util.UUID;

public record RolesResponse(UUID userId, List<RolUsuario> roles) {
}
