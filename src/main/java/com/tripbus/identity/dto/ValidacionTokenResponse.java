package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;

import java.util.UUID;

public record ValidacionTokenResponse(boolean valido, UUID userId, RolUsuario rol, UUID companyId) {
}
