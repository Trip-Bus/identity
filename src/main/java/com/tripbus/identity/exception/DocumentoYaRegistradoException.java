package com.tripbus.identity.exception;

public class DocumentoYaRegistradoException extends RuntimeException {

    public DocumentoYaRegistradoException() {
        super("El documento de identidad ya está registrado");
    }
}
