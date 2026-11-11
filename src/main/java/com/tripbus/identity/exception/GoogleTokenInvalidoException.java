package com.tripbus.identity.exception;

/**
 * El ID token de Google no es válido (firma, emisor, audiencia, vencimiento o correo sin verificar).
 * El motivo exacto solo va al log; la respuesta es siempre la misma (401 GOOGLE_TOKEN_INVALID).
 */
public class GoogleTokenInvalidoException extends RuntimeException {

    private final String motivo;

    public GoogleTokenInvalidoException(String motivo) {
        super("El token de Google no es válido");
        this.motivo = motivo;
    }

    public String getMotivo() {
        return motivo;
    }
}
