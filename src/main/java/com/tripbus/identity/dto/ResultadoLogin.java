package com.tripbus.identity.dto;

/** Resultado de validar usuario y contraseña: los tokens, o el desafío del segundo paso (US-106). */
public record ResultadoLogin(AuthResponse tokens, MfaRequeridoResponse mfa) {

    public static ResultadoLogin conTokens(AuthResponse tokens) {
        return new ResultadoLogin(tokens, null);
    }

    public static ResultadoLogin conSegundoPaso(String mfaToken) {
        return new ResultadoLogin(null, new MfaRequeridoResponse(true, mfaToken));
    }
}
