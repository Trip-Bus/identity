package com.tripbus.identity.exception;

/** El correo ya pertenece a una cuenta vinculada a OTRA cuenta de Google (409 GOOGLE_ACCOUNT_CONFLICT). */
public class GoogleCuentaVinculadaException extends RuntimeException {

    public GoogleCuentaVinculadaException() {
        super("El correo ya está vinculado a otra cuenta de Google");
    }
}
