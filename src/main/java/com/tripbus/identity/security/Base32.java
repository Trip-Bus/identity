package com.tripbus.identity.security;

/** Base32 de la RFC 4648 sin relleno: el formato del secreto que leen las apps autenticadoras (US-106). */
public final class Base32 {

    static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String codificar(byte[] datos) {
        StringBuilder salida = new StringBuilder((datos.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte dato : datos) {
            buffer = (buffer << 8) | (dato & 0xFF);
            bits += 8;
            while (bits >= 5) {
                salida.append(ALFABETO.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            salida.append(ALFABETO.charAt((buffer << (5 - bits)) & 31));
        }
        return salida.toString();
    }
}
