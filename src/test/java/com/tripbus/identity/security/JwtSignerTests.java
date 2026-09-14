package com.tripbus.identity.security;

import com.tripbus.identity.model.RolUsuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSignerTests {

    private JwtKeyProvider keyProvider;
    private JwtSigner jwtSigner;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.addActiveProfile("test");
        keyProvider = new JwtKeyProvider("", "", "identity-key-1", environment);
        jwtSigner = new JwtSigner(keyProvider, 60);
    }

    @Test
    void signedTokenCanBeVerifiedWithTheProviderPublicKeyAndCarriesTheExpectedClaims() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();

        String token = jwtSigner.sign(userId, RolUsuario.OPERADOR, companyId, Duration.ofMinutes(5));

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())
                .build()
                .parseSignedClaims(token);

        assertThat(parsed.getHeader().getKeyId()).isEqualTo("identity-key-1");
        assertThat(parsed.getPayload().getSubject()).isEqualTo(userId.toString());
        assertThat(parsed.getPayload().get("role", String.class)).isEqualTo("OPERADOR");
        assertThat(parsed.getPayload().get("companyId", String.class)).isEqualTo(companyId.toString());
    }

    @Test
    void companyIdClaimIsOmittedWhenItIsNull() {
        String token = jwtSigner.sign(UUID.randomUUID(), RolUsuario.PASAJERO, null, Duration.ofMinutes(5));

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())
                .build()
                .parseSignedClaims(token);

        assertThat(parsed.getPayload().containsKey("companyId")).isFalse();
    }

    @Test
    void aTokenWithAnExpirationInThePastFailsVerification() {
        String token = jwtSigner.sign(UUID.randomUUID(), RolUsuario.PASAJERO, null, Duration.ofMinutes(-5));

        JwtParser parser = Jwts.parser().verifyWith(keyProvider.getPublicKey()).build();

        assertThatThrownBy(() -> parser.parseSignedClaims(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
