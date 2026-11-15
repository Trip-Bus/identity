package com.tripbus.identity.exception;

/** Sin GOOGLE_CLIENT_ID el inicio de sesión con Google está apagado (404 GOOGLE_LOGIN_DISABLED). */
public class GoogleLoginDesactivadoException extends RuntimeException {

    public GoogleLoginDesactivadoException() {
        super("El inicio de sesión con Google no está habilitado");
    }
}
