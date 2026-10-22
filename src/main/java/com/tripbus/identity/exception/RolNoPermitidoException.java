package com.tripbus.identity.exception;

public class RolNoPermitidoException extends RuntimeException {

    public RolNoPermitidoException() {
        super("Solo se pueden crear usuarios con rol OPERADOR o CONDUCTOR");
    }
}
