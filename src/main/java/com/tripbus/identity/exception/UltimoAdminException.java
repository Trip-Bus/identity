package com.tripbus.identity.exception;

public class UltimoAdminException extends RuntimeException {

    public UltimoAdminException() {
        super("Eres el único administrador activo: asigna otro antes de eliminar tu cuenta");
    }
}
