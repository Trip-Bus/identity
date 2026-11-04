package com.tripbus.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * US-106: cifra los secretos TOTP con AES-256-GCM antes de guardarlos. El resultado es Base64 de
 * {@code iv (12 bytes) || texto cifrado + etiqueta (16 bytes)}. El "contexto" (el id del usuario) va como
 * dato autenticado: un secreto copiado a la fila de otro usuario no se puede descifrar.
 *
 * <p>La llave sale de {@code MFA_ENCRYPTION_KEY}: si es Base64 de 32 bytes ({@code openssl rand -base64 32})
 * se usa tal cual; si no, su SHA-256 (mínimo 16 caracteres). Es obligatoria en qa/prod (RequiredSecretsCheck).
 * Vacía (IDE y pruebas) se genera una al azar: las 2FA activadas no sobreviven un reinicio.
 */
@Component
public class CifradorSecretos {

    static final int LARGO_MINIMO_LLAVE = 16;
    private static final Logger log = LoggerFactory.getLogger(CifradorSecretos.class);
    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final int BYTES_LLAVE = 32;
    private static final int BYTES_IV = 12;
    private static final int BITS_ETIQUETA = 128;

    private final SecretKeySpec llave;
    private final SecureRandom random = new SecureRandom();
    /** Para la llave aleatoria de desarrollo (método estático): una sola instancia reutilizada. */
    private static final SecureRandom RANDOM_LLAVE = new SecureRandom();

    @Autowired
    public CifradorSecretos(@Value("${tripbus.identity.mfa.encryption-key:}") String llaveConfigurada) {
        this(llaveDesde(llaveConfigurada));
    }

    CifradorSecretos(byte[] llave) {
        this.llave = new SecretKeySpec(llave, "AES");
    }

    static byte[] llaveDesde(String valor) {
        if (!StringUtils.hasText(valor)) {
            log.warn("MFA_ENCRYPTION_KEY no está definida: se usa una llave aleatoria y la verificación en dos "
                    + "pasos no sobrevive un reinicio (solo para desarrollo)");
            byte[] aleatoria = new byte[BYTES_LLAVE];
            RANDOM_LLAVE.nextBytes(aleatoria);
            return aleatoria;
        }
        String texto = valor.trim();
        if (texto.length() < LARGO_MINIMO_LLAVE) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY debe tener al menos " + LARGO_MINIMO_LLAVE
                    + " caracteres (recomendado: openssl rand -base64 32)");
        }
        byte[] decodificada = base64(texto);
        if (decodificada.length == BYTES_LLAVE) {
            return decodificada;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
        }
    }

    private static byte[] base64(String texto) {
        try {
            return Base64.getDecoder().decode(texto);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    public String cifrar(byte[] plano, String contexto) {
        byte[] iv = new byte[BYTES_IV];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(BITS_ETIQUETA, iv));
            cipher.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            byte[] cifrado = cipher.doFinal(plano);
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    /** @throws IllegalStateException si el texto fue alterado, es de otro contexto o se cifró con otra llave */
    public byte[] descifrar(String cifradoBase64, String contexto) {
        byte[] datos = base64(cifradoBase64);
        if (datos.length <= BYTES_IV) {
            throw new IllegalStateException("Secreto cifrado inválido");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, llave, new GCMParameterSpec(BITS_ETIQUETA, datos, 0, BYTES_IV));
            cipher.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(datos, BYTES_IV, datos.length - BYTES_IV);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar el secreto", e);
        }
    }
}
