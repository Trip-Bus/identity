package com.tripbus.identity.exception;

public class CompanyIdRequeridoException extends RuntimeException {

    public CompanyIdRequeridoException() {
        super("companyId es obligatorio para los roles OPERADOR y CONDUCTOR");
    }
}
