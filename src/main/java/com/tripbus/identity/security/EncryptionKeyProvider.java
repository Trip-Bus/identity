package com.tripbus.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Deriva, a partir de UNA llave maestra AES-256 ("IDENTITY_ENCRYPTION_KEY"), dos sub-llaves
 * independientes (HMAC-SHA256 con separación de dominio) para el cifrado en reposo: una para
 * AES-GCM y otra para derivar el nonce determinista. Ver {@link DocumentoIdentidadConverter}.
 */
@Component
public class EncryptionKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EncryptionKeyProvider.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final byte[] ENCRYPTION_SUBKEY_INFO =
            "identity-encryption-aes-gcm".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NONCE_SUBKEY_INFO =
            "identity-encryption-nonce".getBytes(StandardCharsets.UTF_8);

    private final SecretKey encryptionKey;
    private final SecretKey nonceDerivationKey;

    public EncryptionKeyProvider(
            @Value("${security.encryption.key:}") String base64Key,
            Environment environment) {
        byte[] masterKey = resolveMasterKey(base64Key, environment);
        this.encryptionKey = new SecretKeySpec(hmac(masterKey, ENCRYPTION_SUBKEY_INFO), AES_ALGORITHM);
        this.nonceDerivationKey = new SecretKeySpec(hmac(masterKey, NONCE_SUBKEY_INFO), HMAC_ALGORITHM);
    }

    public SecretKey getEncryptionKey() {
        return encryptionKey;
    }

    public SecretKey getNonceDerivationKey() {
        return nonceDerivationKey;
    }

    private static byte[] resolveMasterKey(String base64Key, Environment environment) {
        if (base64Key != null && !base64Key.isBlank()) {
            return decodeAndValidate(base64Key);
        }

        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            log.warn("No se encontro la variable de entorno 'IDENTITY_ENCRYPTION_KEY'. Generando una llave "
                    + "AES-256 EFIMERA en memoria solo porque el perfil activo es local/test. Esto NUNCA debe "
                    + "ocurrir en los perfiles demo o production: ahi la llave debe montarse como secreto de "
                    + "entorno.");
            return generateEphemeralKey();
        }

        throw new IllegalStateException(
                "La variable de entorno 'IDENTITY_ENCRYPTION_KEY' no esta definida y el perfil activo ('"
                        + String.join(",", environment.getActiveProfiles()) + "') no permite generar una llave "
                        + "efimera. Define 'IDENTITY_ENCRYPTION_KEY' con una llave AES-256 codificada en base64 "
                        + "(32 bytes) antes de iniciar el servicio.");
    }

    private static byte[] decodeAndValidate(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "La variable de entorno 'IDENTITY_ENCRYPTION_KEY' no contiene un valor base64 valido.", e);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "La variable de entorno 'IDENTITY_ENCRYPTION_KEY' debe decodificar a exactamente "
                            + KEY_LENGTH_BYTES + " bytes (AES-256), pero tiene " + decoded.length + " bytes.");
        }
        return decoded;
    }

    private static byte[] generateEphemeralKey() {
        byte[] key = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static byte[] hmac(byte[] key, byte[] info) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(info);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo derivar la llave de cifrado en reposo", e);
        }
    }
}
