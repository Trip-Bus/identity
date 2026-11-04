package com.tripbus.identity.util;

/**
 * HT-10: los logs nunca llevan un correo completo. Se deja la primera letra y el dominio
 * ("ana.perez@tripbus.co" → "a***@tripbus.co"), suficiente para soporte sin exponer el dato.
 */
public final class Enmascarar {

    static final String OCULTO = "***";

    private Enmascarar() {
    }

    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return OCULTO;
        }
        int arroba = email.indexOf('@');
        if (arroba <= 0) {
            return OCULTO;
        }
        return email.charAt(0) + OCULTO + email.substring(arroba);
    }
}
