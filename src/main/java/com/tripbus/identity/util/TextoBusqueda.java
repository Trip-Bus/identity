package com.tripbus.identity.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * US-102: forma de comparar textos en la búsqueda de usuarios sin distinguir mayúsculas ni tildes
 * ("José PÉREZ" → "jose perez"). Se aplica igual al texto guardado (columna usuarios.busqueda) y a lo
 * que escribe el administrador, así la consulta es un LIKE simple que aprovecha el índice trigram (V6).
 */
public final class TextoBusqueda {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    private TextoBusqueda() {
    }

    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = MARCAS.matcher(Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Texto buscable de un usuario: nombre, email y documento en una sola columna. */
    public static String deUsuario(String nombre, String email, String documentoIdentidad) {
        return normalizar(nombre + " " + email + " " + documentoIdentidad);
    }
}
