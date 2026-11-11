package com.tripbus.identity.exception;

/**
 * Primera vez con Google sin aceptar la política de datos o sin documento (422 GOOGLE_REGISTRO_INCOMPLETO).
 * Lleva el correo y el nombre del token para que el frontend precargue el formulario.
 */
public class GoogleRegistroIncompletoException extends RuntimeException {

    private final String email;
    private final String nombre;

    public GoogleRegistroIncompletoException(String email, String nombre) {
        super("Para crear la cuenta acepta la política de tratamiento de datos e indica tu documento de identidad");
        this.email = email;
        this.nombre = nombre;
    }

    public String getEmail() {
        return email;
    }

    public String getNombre() {
        return nombre;
    }
}
