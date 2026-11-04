package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** US-106: desactivar la 2FA exige la contraseña y un código vigente. */
public record DesactivarMfaRequest(

        @NotBlank(message = "La contraseña es obligatoria")
        String password,

        @NotBlank(message = "El código es obligatorio")
        String code) {
}
