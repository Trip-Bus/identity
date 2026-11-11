package com.tripbus.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /auth/google. {@code idToken} es la credencial de Google Identity Services. Los demás campos solo
 * se usan la primera vez (registro): sin {@code aceptaPoliticaDatos: true} y {@code documentoIdentidad}
 * la respuesta es 422 GOOGLE_REGISTRO_INCOMPLETO.
 */
public record GoogleLoginRequest(

        @NotBlank(message = "El token de Google es obligatorio")
        String idToken,

        Boolean aceptaPoliticaDatos,

        String documentoIdentidad,

        String nombre) {

    public GoogleLoginRequest(String idToken) {
        this(idToken, null, null, null);
    }
}
