package com.tripbus.identity.exception;

public class UsuarioNoEncontradoException extends RuntimeException {

    public UsuarioNoEncontradoException() {
        super("El usuario solicitado no existe");
    }
}
