package com.tripbus.identity.exception;

/** Se superó un límite de peticiones (p. ej. reenvíos del correo de verificación): 429 TOO_MANY_REQUESTS. */
public class DemasiadasSolicitudesException extends RuntimeException {

    public DemasiadasSolicitudesException(String message) {
        super(message);
    }
}
