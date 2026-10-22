package com.tripbus.identity.exception;

public class CatalogNoDisponibleException extends RuntimeException {

    public CatalogNoDisponibleException() {
        super("No se pudo validar la empresa: el catálogo no está disponible. Intente de nuevo");
    }
}
