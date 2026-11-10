package com.tripbus.identity.exception;

import org.springframework.http.HttpStatus;

/** US-106: errores de la verificación en dos pasos, cada uno con su status y código. */
public class MfaException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    MfaException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** /2fa/setup o /2fa/enable sobre una 2FA ya activa. */
    public static MfaException yaActiva() {
        return new MfaException(HttpStatus.CONFLICT, "MFA_ALREADY_ENABLED", "La verificación en dos pasos ya está activa");
    }

    /** /2fa/enable sin haber llamado antes a /2fa/setup. */
    public static MfaException sinConfigurar() {
        return new MfaException(HttpStatus.CONFLICT, "MFA_NOT_SET_UP", "Primero genera el secreto con /users/me/2fa/setup");
    }

    /** /2fa/disable sin una 2FA activa. */
    public static MfaException noActiva() {
        return new MfaException(HttpStatus.CONFLICT, "MFA_NOT_ENABLED", "La verificación en dos pasos no está activa");
    }

    /** Código incorrecto, vencido o ya usado al activar o desactivar. */
    public static MfaException codigoInvalido() {
        return new MfaException(HttpStatus.BAD_REQUEST, "INVALID_MFA_CODE", "El código no es válido");
    }

    /** Código incorrecto, vencido o ya usado en el segundo paso del login. */
    public static MfaException codigoInvalidoEnLogin() {
        return new MfaException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "El código no es válido");
    }

    /** mfaToken inexistente, vencido, ya usado o invalidado por 5 fallos. */
    public static MfaException desafioInvalido() {
        return new MfaException(HttpStatus.UNAUTHORIZED, "MFA_TOKEN_INVALID",
                "La verificación venció o no es válida: inicia sesión de nuevo");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
