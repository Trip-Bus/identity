package com.tripbus.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/** US-106: segundo paso del login. Lleva el código de la app (code) o uno de recuperación (recoveryCode). */
public record MfaLoginRequest(

        @NotBlank(message = "El mfaToken es obligatorio")
        String mfaToken,

        String code,

        String recoveryCode) {

    @JsonIgnore
    @AssertTrue(message = "Envía code o recoveryCode")
    public boolean isConCodigo() {
        return (code != null && !code.isBlank()) || (recoveryCode != null && !recoveryCode.isBlank());
    }
}
