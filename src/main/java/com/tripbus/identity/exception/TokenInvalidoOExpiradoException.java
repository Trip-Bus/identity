package com.tripbus.identity.exception;

public class TokenInvalidoOExpiradoException extends RuntimeException {

    public TokenInvalidoOExpiradoException() {
        super("El token de recuperación no es válido o ya expiró");
    }
}
