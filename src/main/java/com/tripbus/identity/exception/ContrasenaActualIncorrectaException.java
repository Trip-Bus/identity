package com.tripbus.identity.exception;

/** US-129: la contraseña actual no coincide al intentar cambiarla: 400 INVALID_CURRENT_PASSWORD. */
public class ContrasenaActualIncorrectaException extends RuntimeException {

    public ContrasenaActualIncorrectaException() {
        super("La contraseña actual no es correcta");
    }
}
