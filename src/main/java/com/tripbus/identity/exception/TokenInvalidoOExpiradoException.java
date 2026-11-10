package com.tripbus.identity.exception;

public class TokenInvalidoOExpiradoException extends RuntimeException {

    public TokenInvalidoOExpiradoException() {
        // Recuperación de contraseña (US-004) y verificación de correo (US-130).
        super("El enlace no es válido, ya se usó o ya expiró");
    }
}
