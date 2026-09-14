package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CambiarRolRequest(

        @NotNull(message = "El rol es obligatorio")
        RolUsuario rol,

        UUID companyId) {
}
