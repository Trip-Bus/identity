package com.tripbus.identity.exception;

public class EmailYaRegistradoException extends RuntimeException {

    public EmailYaRegistradoException() {
        super("El correo electrónico ya está registrado");
    }
}
