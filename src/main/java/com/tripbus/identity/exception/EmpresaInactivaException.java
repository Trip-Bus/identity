package com.tripbus.identity.exception;

public class EmpresaInactivaException extends RuntimeException {

    public EmpresaInactivaException() {
        super("La empresa indicada está inactiva");
    }
}
