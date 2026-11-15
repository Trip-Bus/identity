package com.tripbus.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Emisor de ID tokens "de Google" para las pruebas: genera sus propias llaves RSA y, si se pide, publica el
 * JWKS en un servidor HTTP local. Ninguna prueba llama a Google.
 */
public final class GoogleFalso implements AutoCloseable {

    public static final String CLIENT_ID = "tripbus-pruebas.apps.googleusercontent.com";
    public static final String KID = "kid-google-pruebas";
    public static final String EMISOR = "https://accounts.google.com";

    private final RSAKey llave = generar(KID);
    /** Mismo kid que la buena, otra llave: un token firmado con ella tiene la firma inválida. */
    private final RSAKey impostora = generar(KID);
    private final AtomicInteger descargas = new AtomicInteger();
    private HttpServer servidor;

    private static RSAKey generar(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAKey llavePublica() {
        return llave.toPublicJWK();
    }

    public String jwks() {
        return new JWKSet(llave.toPublicJWK()).toString();
    }

    /** Claims de un token válido: iss de Google, aud = CLIENT_ID, vence en una hora, correo verificado. */
    public static JWTClaimsSet.Builder claims(String sub, String email, String nombre) {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(EMISOR)
                .audience(CLIENT_ID)
                .subject(sub)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(3600)))
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", nombre);
    }

    public String token(String sub, String email, String nombre) {
        return firmar(claims(sub, email, nombre).build());
    }

    public String firmar(JWTClaimsSet claims) {
        return firmar(claims, llave);
    }

    public String firmarConOtraLlave(JWTClaimsSet claims) {
        return firmar(claims, impostora);
    }

    private static String firmar(JWTClaimsSet claims, RSAKey conLlave) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(conLlave.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(conLlave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Publica el JWKS en http://localhost:{puerto}/oauth2/v3/certs (Cache-Control como Google). */
    public String iniciarServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/oauth2/v3/certs", intercambio -> {
            descargas.incrementAndGet();
            byte[] cuerpo = jwks().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.getResponseHeaders().add("Cache-Control", "public, max-age=21600, must-revalidate");
            intercambio.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(cuerpo);
            }
        });
        servidor.start();
        return "http://localhost:" + servidor.getAddress().getPort() + "/oauth2/v3/certs";
    }

    public int descargas() {
        return descargas.get();
    }

    @Override
    public void close() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }
}
