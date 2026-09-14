package com.tripbus.identity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);
    private static final String RSA_ALGORITHM = "RSA";
    private static final int EPHEMERAL_KEY_SIZE_BITS = 2048;

    private final KeyPair keyPair;
    private final String keyId;

    public JwtKeyProvider(
            @Value("${security.jwt.private-key-path:}") String privateKeyPath,
            @Value("${security.jwt.public-key-path:}") String publicKeyPath,
            @Value("${security.jwt.key-id:identity-key-1}") String keyId,
            Environment environment) {
        this.keyId = keyId;
        this.keyPair = resolveKeyPair(privateKeyPath, publicKeyPath, environment);
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public String getKeyId() {
        return keyId;
    }

    private KeyPair resolveKeyPair(String privateKeyPath, String publicKeyPath, Environment environment) {
        if (isReadableFile(privateKeyPath) && isReadableFile(publicKeyPath)) {
            return loadKeyPairFromPem(privateKeyPath, publicKeyPath);
        }

        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            log.warn("No se encontraron llaves JWT en 'security.jwt.private-key-path' / "
                    + "'security.jwt.public-key-path'. Generando un par de llaves RSA EFIMERO en "
                    + "memoria solo porque el perfil activo es local/test. Esto NUNCA debe ocurrir "
                    + "en los perfiles demo o production: ahi las llaves deben montarse desde un secreto.");
            return generateEphemeralKeyPair();
        }

        throw new IllegalStateException(
                "No se encontraron las llaves JWT configuradas en 'security.jwt.private-key-path' ('"
                        + privateKeyPath + "') / 'security.jwt.public-key-path' ('" + publicKeyPath
                        + "'), y el perfil activo no permite generar una llave efimera. "
                        + "Monta el par de llaves RSA como secreto antes de iniciar el servicio.");
    }

    private static boolean isReadableFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        Path resolved = Path.of(path);
        return Files.isRegularFile(resolved) && Files.isReadable(resolved);
    }

    private static KeyPair loadKeyPairFromPem(String privateKeyPath, String publicKeyPath) {
        try {
            PrivateKey privateKey = readPemPrivateKey(Path.of(privateKeyPath));
            PublicKey publicKey = readPemPublicKey(Path.of(publicKeyPath));
            return new KeyPair(publicKey, privateKey);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("No se pudieron cargar las llaves JWT desde '"
                    + privateKeyPath + "' / '" + publicKeyPath + "'", e);
        }
    }

    private static PrivateKey readPemPrivateKey(Path path) throws IOException, GeneralSecurityException {
        byte[] decoded = decodePem(Files.readString(path));
        return KeyFactory.getInstance(RSA_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private static PublicKey readPemPublicKey(Path path) throws IOException, GeneralSecurityException {
        byte[] decoded = decodePem(Files.readString(path));
        return KeyFactory.getInstance(RSA_ALGORITHM).generatePublic(new X509EncodedKeySpec(decoded));
    }

    private static byte[] decodePem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
            generator.initialize(EPHEMERAL_KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo generar un par de llaves RSA efimero", e);
        }
    }
}
