package com.tripbus.identity.service;

import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

/**
 * Alta concurrente (registro o creación por un ADMIN): otra petición con el mismo email/documento pasó
 * la verificación previa al mismo tiempo y el índice único de la BD rechazó esta. Se traduce al mismo
 * 409 que el duplicado normal en vez de un 500.
 */
final class ConflictosUnicidad {

    private ConflictosUnicidad() {
    }

    static RuntimeException traducir(DataIntegrityViolationException ex) {
        String restriccion = nombreRestriccion(ex);
        if (restriccion.contains("email")) {
            return new EmailYaRegistradoException();
        }
        if (restriccion.contains("documento")) {
            return new DocumentoYaRegistradoException();
        }
        return ex;
    }

    /** Nombre del índice/constraint que falló (uq_usuarios_email_lower, uq_usuarios_documento_identidad). */
    private static String nombreRestriccion(DataIntegrityViolationException ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        String mensaje = ex.getMostSpecificCause().getMessage();
        return mensaje != null ? mensaje.toLowerCase(Locale.ROOT) : "";
    }
}
