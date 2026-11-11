package com.tripbus.identity.exception;

/** No se pudieron descargar las llaves públicas de Google y no hay copia en caché (503). */
public class GoogleNoDisponibleException extends RuntimeException {

    public GoogleNoDisponibleException() {
        super("No fue posible validar el token con Google, intenta más tarde");
    }
}
