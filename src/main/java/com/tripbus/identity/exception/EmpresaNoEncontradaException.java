package com.tripbus.identity.exception;

public class EmpresaNoEncontradaException extends RuntimeException {

    public EmpresaNoEncontradaException() {
        super("La empresa indicada no existe en el catálogo");
    }
}
