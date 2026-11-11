package com.tripbus.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.tripbus.identity.exception.GoogleTokenInvalidoException;
import com.tripbus.identity.util.EmailNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Verifica el ID token de Google Identity Services (flujo de ID token, sin client secret): firma RS256 con
 * las llaves públicas de Google, {@code iss} de Google, {@code aud} = GOOGLE_CLIENT_ID, {@code exp} vigente
 * (tolerancia de 60 s por desfase de relojes) y {@code email_verified = true}. Usa nimbus-jose-jwt, que el
 * proyecto ya tenía para publicar su propio JWKS.
 */
@Component
public class GoogleIdTokenVerifier {

    static final Set<String> EMISORES = Set.of("accounts.google.com", "https://accounts.google.com");
    static final Duration TOLERANCIA_RELOJ = Duration.ofSeconds(60);

    /** Lo que identity toma del token: el {@code sub} estable, el correo normalizado y el nombre (opcional). */
    public record IdentidadGoogle(String sub, String email, String nombre) {
    }

    private final String clientId;
    private final GoogleLlavesPublicas llaves;
    private final Clock clock;

    @Autowired
    public GoogleIdTokenVerifier(@Value("${tripbus.identity.google.client-id:}") String clientId,
                                 GoogleLlavesPublicas llaves) {
        this(clientId, llaves, Clock.systemUTC());
    }

    GoogleIdTokenVerifier(String clientId, GoogleLlavesPublicas llaves, Clock clock) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.llaves = llaves;
        this.clock = clock;
    }

    /** Sin GOOGLE_CLIENT_ID el inicio de sesión con Google está apagado. */
    public boolean habilitado() {
        return !clientId.isEmpty();
    }

    public String clientId() {
        return clientId;
    }

    /** @throws GoogleTokenInvalidoException si el token no pasa alguna de las validaciones */
    public IdentidadGoogle verificar(String idToken) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(idToken);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new GoogleTokenInvalidoException("no es un JWT firmado");
        }
        verificarFirma(jwt);
        return validarClaims(claims);
    }

    private void verificarFirma(SignedJWT jwt) {
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new GoogleTokenInvalidoException("algoritmo " + jwt.getHeader().getAlgorithm());
        }
        String kid = jwt.getHeader().getKeyID();
        RSAKey llave = kid == null ? null : llaves.buscar(kid);
        if (llave == null) {
            throw new GoogleTokenInvalidoException("kid desconocido");
        }
        boolean valida;
        try {
            valida = jwt.verify(new RSASSAVerifier(llave));
        } catch (JOSEException e) {
            valida = false;
        }
        if (!valida) {
            throw new GoogleTokenInvalidoException("firma inválida");
        }
    }

    private IdentidadGoogle validarClaims(JWTClaimsSet claims) {
        if (!EMISORES.contains(String.valueOf(claims.getIssuer()))) {
            throw new GoogleTokenInvalidoException("iss " + claims.getIssuer());
        }
        List<String> audiencia = claims.getAudience();
        if (audiencia.size() != 1 || !clientId.equals(audiencia.get(0))) {
            throw new GoogleTokenInvalidoException("aud distinta");
        }
        Date exp = claims.getExpirationTime();
        Instant ahora = clock.instant();
        if (exp == null || ahora.isAfter(exp.toInstant().plus(TOLERANCIA_RELOJ))) {
            throw new GoogleTokenInvalidoException("vencido");
        }
        Object verificado = claims.getClaim("email_verified");
        if (!Boolean.TRUE.equals(verificado) && !"true".equals(verificado)) {
            throw new GoogleTokenInvalidoException("email no verificado");
        }
        String sub = claims.getSubject();
        String email = texto(claims, "email");
        if (!StringUtils.hasText(sub) || !StringUtils.hasText(email)) {
            throw new GoogleTokenInvalidoException("sin sub o email");
        }
        String nombre = texto(claims, "name");
        return new IdentidadGoogle(sub, EmailNormalizer.normalizar(email),
                StringUtils.hasText(nombre) ? nombre.trim() : null);
    }

    private static String texto(JWTClaimsSet claims, String nombre) {
        return claims.getClaim(nombre) instanceof String valor ? valor : null;
    }
}
