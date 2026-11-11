package com.tripbus.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tripbus.identity.exception.GoogleTokenInvalidoException;
import com.tripbus.identity.security.GoogleIdTokenVerifier.IdentidadGoogle;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleIdTokenVerifierTest {

    private static final String SUB = "1234567890";

    private final GoogleFalso google = new GoogleFalso();
    private final GoogleLlavesPublicas llaves = mock(GoogleLlavesPublicas.class);
    private final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(GoogleFalso.CLIENT_ID, llaves);

    GoogleIdTokenVerifierTest() {
        when(llaves.buscar(GoogleFalso.KID)).thenReturn(google.llavePublica());
    }

    private void rechaza(String token, String motivo) {
        assertThatThrownBy(() -> verifier.verificar(token))
                .isInstanceOf(GoogleTokenInvalidoException.class)
                .hasMessage("El token de Google no es válido")
                .extracting(e -> ((GoogleTokenInvalidoException) e).getMotivo()).asString().contains(motivo);
    }

    @Test
    void tokenValidoDevuelveSubCorreoNormalizadoYNombre() {
        IdentidadGoogle identidad = verifier.verificar(google.token(SUB, " Ana.Gomez@Gmail.com", " Ana Gómez "));

        assertThat(identidad).isEqualTo(new IdentidadGoogle(SUB, "ana.gomez@gmail.com", "Ana Gómez"));
        assertThat(verifier.habilitado()).isTrue();
        assertThat(verifier.clientId()).isEqualTo(GoogleFalso.CLIENT_ID);
    }

    @Test
    void aceptaElEmisorSinEsquemaEmailVerifiedComoTextoYSinNombre() {
        String token = google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", null)
                .issuer("accounts.google.com").claim("email_verified", "true").build());

        assertThat(verifier.verificar(token).nombre()).isNull();
    }

    @Test
    void firmaInvalida() {
        rechaza(google.firmarConOtraLlave(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").build()), "firma");
    }

    @Test
    void audDistinta() {
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").audience("otra-app").build()), "aud");
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana")
                .audience(List.of(GoogleFalso.CLIENT_ID, "otra-app")).build()), "aud");
    }

    @Test
    void emisorDistinto() {
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").issuer("https://evil.example").build()),
                "iss");
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").issuer(null).build()), "iss");
    }

    @Test
    void vencidoMasAllaDeLaToleranciaDe60Segundos() {
        Instant exp = Instant.parse("2026-11-11T10:00:00Z");
        String token = google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana")
                .expirationTime(Date.from(exp)).build());

        GoogleIdTokenVerifier dentro = new GoogleIdTokenVerifier(GoogleFalso.CLIENT_ID, llaves,
                Clock.fixed(exp.plusSeconds(60), ZoneOffset.UTC));
        assertThat(dentro.verificar(token).sub()).isEqualTo(SUB);

        GoogleIdTokenVerifier fuera = new GoogleIdTokenVerifier(GoogleFalso.CLIENT_ID, llaves,
                Clock.fixed(exp.plusSeconds(61), ZoneOffset.UTC));
        assertThatThrownBy(() -> fuera.verificar(token)).isInstanceOf(GoogleTokenInvalidoException.class);
    }

    @Test
    void sinExp() {
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").expirationTime(null).build()), "vencido");
    }

    @Test
    void emailNoVerificado() {
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").claim("email_verified", false).build()),
                "email no verificado");
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").claim("email_verified", null).build()),
                "email no verificado");
    }

    @Test
    void sinSubOSinEmail() {
        rechaza(google.firmar(GoogleFalso.claims(" ", "ana@gmail.com", "Ana").build()), "sin sub");
        rechaza(google.firmar(GoogleFalso.claims(SUB, null, "Ana").build()), "sin sub");
        rechaza(google.firmar(GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").claim("email", 42).build()), "sin sub");
    }

    @Test
    void noEsUnJwtFirmado() {
        rechaza("esto-no-es-un-jwt", "no es un JWT");
        rechaza("eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.", "no es un JWT");
    }

    @Test
    void algoritmoDistintoDeRs256() throws JOSEException {
        SignedJWT hs256 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(GoogleFalso.KID).build(),
                GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").build());
        hs256.sign(new MACSigner(new byte[32]));
        rechaza(hs256.serialize(), "algoritmo");
    }

    @Test
    void kidDesconocidoOAusente() {
        JWTClaimsSet claims = GoogleFalso.claims(SUB, "ana@gmail.com", "Ana").build();
        rechaza(firmarSinKid(claims), "kid");
        when(llaves.buscar(GoogleFalso.KID)).thenReturn(null);
        rechaza(google.firmar(claims), "kid");
    }

    @Test
    void llaveQueNoSirveParaVerificar() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(1024); // RSASSAVerifier exige al menos 2048 bits: lanza JOSEException
        RSAKey corta = new RSAKey.Builder((RSAPublicKey) generador.generateKeyPair().getPublic())
                .keyID(GoogleFalso.KID).build();
        when(llaves.buscar(GoogleFalso.KID)).thenReturn(corta);

        rechaza(google.token(SUB, "ana@gmail.com", "Ana"), "firma");
    }

    @Test
    void sinClientIdQuedaApagado() {
        assertThat(new GoogleIdTokenVerifier(null, llaves).habilitado()).isFalse();
        assertThat(new GoogleIdTokenVerifier("  ", llaves).habilitado()).isFalse();
    }

    private String firmarSinKid(JWTClaimsSet claims) {
        String conKid = google.firmar(claims);
        // Cambia solo el header: la firma deja de cuadrar, pero el verificador debe cortar antes por el kid.
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return header + conKid.substring(conKid.indexOf('.'));
    }
}
