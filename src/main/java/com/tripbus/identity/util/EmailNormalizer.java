package com.tripbus.identity.util;

import java.util.Locale;

/**
 * Forma canónica de un correo: sin espacios alrededor y en minúsculas. Se aplica al registrar,
 * al iniciar sesión y al pedir recuperación de contraseña, y la BD lo refuerza con un índice
 * único sobre lower(email) (V5), así "Ana@Mail.com " y "ana@mail.com" son la misma cuenta.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /** {@code null} se conserva para que la validación (@NotBlank) lo reporte como obligatorio. */
    public static String normalizar(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
