package com.tripbus.identity.exception;

public class CuentaSuspendidaException extends RuntimeException {

    public CuentaSuspendidaException() {
        super("La cuenta está suspendida");
    }
}
