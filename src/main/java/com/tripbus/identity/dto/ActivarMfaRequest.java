package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** US-106: código de 6 dígitos de la app autenticadora para activar la 2FA. */
public record ActivarMfaRequest(

        @NotBlank(message = "El código es obligatorio")
        String code) {
}
