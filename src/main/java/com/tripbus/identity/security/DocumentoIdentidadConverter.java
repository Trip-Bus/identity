package com.tripbus.identity.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra/descifra el documento de identidad en reposo con AES-256-GCM.
 *
 * <p>El nonce NO es aleatorio: se deriva de forma determinista con HMAC-SHA256(nonceKey, texto
 * plano). Esto es intencional y necesario: {@code UsuarioRepository.existsByDocumentoIdentidad}
 * y el indice UNIQUE de la columna dependen de que el mismo documento produzca siempre el mismo
 * texto cifrado, para poder seguir detectando documentos duplicados sin descifrar toda la tabla.
 * La llave de derivacion del nonce es independiente de la llave de cifrado (ver
 * {@link EncryptionKeyProvider}), por lo que un nonce nunca se reutiliza entre textos planos
 * distintos.
 */
@Converter
@Component
public class DocumentoIdentidadConverter implements AttributeConverter<String, String> {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final EncryptionKeyProvider keyProvider;

    public DocumentoIdentidadConverter(EncryptionKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }

        byte[] plaintext = attribute.getBytes(StandardCharsets.UTF_8);
        byte[] nonce = deterministicNonce(plaintext);
        byte[] ciphertext = cipherOperate(Cipher.ENCRYPT_MODE, nonce, plaintext);

        byte[] payload = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(payload);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }

        byte[] payload = Base64.getDecoder().decode(dbData);
        byte[] nonce = Arrays.copyOfRange(payload, 0, GCM_NONCE_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(payload, GCM_NONCE_LENGTH_BYTES, payload.length);
        byte[] plaintext = cipherOperate(Cipher.DECRYPT_MODE, nonce, ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private byte[] cipherOperate(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(mode, keyProvider.getEncryptionKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar/descifrar el documento de identidad", e);
        }
    }

    private byte[] deterministicNonce(byte[] plaintext) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keyProvider.getNonceDerivationKey());
            byte[] digest = mac.doFinal(plaintext);
            return Arrays.copyOf(digest, GCM_NONCE_LENGTH_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo derivar el nonce de cifrado", e);
        }
    }
}
