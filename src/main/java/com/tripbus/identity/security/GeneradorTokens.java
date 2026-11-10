package com.tripbus.identity.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens de un solo uso que viajan por correo (recuperación de contraseña y verificación de correo):
 * 32 bytes aleatorios en Base64 URL (van en el enlace). En la BD solo se guarda su hash SHA-256.
 */
@Component
public class GeneradorTokens {

    private static final int BYTES_ALEATORIOS = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final String algoritmoHash;

    public GeneradorTokens() {
        this("SHA-256");
    }

    /** Solo para probar el caso de una JVM sin el algoritmo. */
    GeneradorTokens(String algoritmoHash) {
        this.algoritmoHash = algoritmoHash;
    }

    public String generar() {
        byte[] randomBytes = new byte[BYTES_ALEATORIOS];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hash(String tokenPlano) {
        try {
            byte[] hash = MessageDigest.getInstance(algoritmoHash).digest(tokenPlano.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algoritmoHash + " no está disponible en esta JVM", e);
        }
    }
}
