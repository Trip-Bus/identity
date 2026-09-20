package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record UsuarioResponse(

        @Schema(description = "Identificador del usuario", example = "18bd9885-32f1-4f6c-a889-0e6bfc18907d")
        UUID id,

        @Schema(description = "Correo electrónico del usuario", example = "pasajero@tripbus.com")
        String email,

        @Schema(description = "Nombre completo del usuario", example = "Juan Pérez")
        String nombre,

        @Schema(description = "Rol asignado al usuario", example = "PASAJERO")
        RolUsuario rol,

        @Schema(description = "Empresa asociada; presente solo si el rol es OPERADOR o CONDUCTOR",
                example = "3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        UUID companyId,

        @Schema(description = "Indica si la cuenta está activa (false = suspendida, no puede iniciar sesión)",
                example = "true")
        boolean activo) {
}
