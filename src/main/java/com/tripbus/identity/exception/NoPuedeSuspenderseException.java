package com.tripbus.identity.exception;

public class NoPuedeSuspenderseException extends RuntimeException {

    public NoPuedeSuspenderseException() {
        super("Un administrador no puede suspender su propia cuenta");
    }
}
