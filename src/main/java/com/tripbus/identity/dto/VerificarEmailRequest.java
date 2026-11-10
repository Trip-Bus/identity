package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** US-130: el token que llegó en el enlace {frontend-url}/verificar-correo?token=... */
public record VerificarEmailRequest(

        @NotBlank(message = "El token es obligatorio")
        String token) {
}
