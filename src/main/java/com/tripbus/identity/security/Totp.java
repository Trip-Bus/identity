package com.tripbus.identity.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * US-106: TOTP de la RFC 6238 (HOTP de la RFC 4226 sobre pasos de 30 s) con HmacSHA1 y 6 dígitos, lo que
 * usan Google Authenticator, Microsoft Authenticator, Authy, etc. Solo javax.crypto: sin dependencias.
 */
public final class Totp {

    public static final int DIGITOS = 6;
    public static final long PASO_SEGUNDOS = 30;
    /** Pasos aceptados alrededor del actual (±1 = ±30 s) por la diferencia de reloj con el teléfono. */
    static final int VENTANA = 1;
    static final int BYTES_SECRETO = 20;
    private static final String HMAC = "HmacSHA1";
    private static final Pattern FORMATO_CODIGO = Pattern.compile("\\d{" + DIGITOS + "}");
    private static final int[] POTENCIAS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 160 bits aleatorios, el tamaño que recomienda la RFC 4226 para HmacSHA1. */
    public static byte[] nuevoSecreto() {
        byte[] secreto = new byte[BYTES_SECRETO];
        RANDOM.nextBytes(secreto);
        return secreto;
    }

    public static long paso(Instant instante) {
        return Math.floorDiv(instante.getEpochSecond(), PASO_SEGUNDOS);
    }

    public static String codigo(byte[] secreto, long paso) {
        return codigo(secreto, paso, DIGITOS);
    }

    /** HOTP(secreto, paso) con truncamiento dinámico; {@code digitos} entre 1 y 8 (los vectores de la RFC usan 8). */
    static String codigo(byte[] secreto, long paso, int digitos) {
        byte[] hash;
        try {
            // HmacSHA1 es el algoritmo de TOTP (RFC 6238/4226) y el único que todas las apps autenticadoras
            // aceptan. Las colisiones de SHA-1 no afectan a HMAC, así que aquí no es un hash débil.
            Mac mac = Mac.getInstance(HMAC); // NOSONAR java:S4790 (HMAC-SHA1 de TOTP, no un hash de integridad)
            mac.init(new SecretKeySpec(secreto, HMAC));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(paso).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " no está disponible en esta JVM", e);
        }
        int desplazamiento = hash[hash.length - 1] & 0x0F;
        int binario = ((hash[desplazamiento] & 0x7F) << 24)
                | ((hash[desplazamiento + 1] & 0xFF) << 16)
                | ((hash[desplazamiento + 2] & 0xFF) << 8)
                | (hash[desplazamiento + 3] & 0xFF);
        int otp = binario % POTENCIAS[digitos];
        return String.format("%0" + digitos + "d", otp);
    }

    /**
     * Busca el código en el paso actual y en los vecinos (±1). Solo acepta pasos posteriores a
     * {@code ultimoPasoUsado}: así un código ya usado (o uno anterior) se rechaza.
     *
     * @return el paso en el que coincidió, o vacío si el código no es válido
     */
    public static OptionalLong verificar(byte[] secreto, String codigo, Instant ahora, Long ultimoPasoUsado) {
        if (codigo == null || !FORMATO_CODIGO.matcher(codigo).matches()) {
            return OptionalLong.empty();
        }
        byte[] recibido = codigo.getBytes(StandardCharsets.US_ASCII);
        long actual = paso(ahora);
        for (long candidato = actual - VENTANA; candidato <= actual + VENTANA; candidato++) {
            boolean usado = ultimoPasoUsado != null && candidato <= ultimoPasoUsado;
            if (!usado && MessageDigest.isEqual(recibido,
                    codigo(secreto, candidato).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(candidato);
            }
        }
        return OptionalLong.empty();
    }

    /** URI que las apps leen del QR: otpauth://totp/Emisor:cuenta?secret=...&issuer=Emisor&... */
    public static String uri(String emisor, String cuenta, String secretoBase32) {
        String emisorCodificado = codificar(emisor);
        return "otpauth://totp/" + emisorCodificado + ":" + codificar(cuenta)
                + "?secret=" + secretoBase32
                + "&issuer=" + emisorCodificado
                + "&algorithm=SHA1&digits=" + DIGITOS + "&period=" + PASO_SEGUNDOS;
    }

    private static String codificar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
