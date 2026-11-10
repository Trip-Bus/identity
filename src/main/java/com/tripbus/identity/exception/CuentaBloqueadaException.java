package com.tripbus.identity.exception;

public class CuentaBloqueadaException extends RuntimeException {
    private final long retryAfterSeconds;
    public CuentaBloqueadaException(long retryAfterSeconds) {
        super("La cuenta está bloqueada temporalmente");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
