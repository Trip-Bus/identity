package com.tripbus.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @Schema(description = "Correo electrónico registrado", example = "pasajero@tripbus.com")
        @NotBlank(message = "El correo electrónico es obligatorio")
        String email,

        @Schema(description = "Contraseña en texto plano", example = "Passw0rd1")
        @NotBlank(message = "La contraseña es obligatoria")
        String password) {
}
