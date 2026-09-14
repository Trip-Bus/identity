package com.tripbus.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.tripbus.identity.model.RolUsuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import java.security.PublicKey;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JwksController.class)
@Import(JwksControllerTests.TestConfig.class)
class JwksControllerTests {

    private static final String JWKS_PATH = "/.well-known/jwks.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtKeyProvider keyProvider;

    @TestConfiguration
    static class TestConfig {

        @Bean
        JwtKeyProvider jwtKeyProvider() {
            MockEnvironment environment = new MockEnvironment();
            environment.addActiveProfile("test");
            return new JwtKeyProvider("", "", "identity-key-1", environment);
        }
    }

    @Test
    void jwksEndpointRespondsWithOkAndJsonWithoutRequiringAuthentication() throws Exception {
        mockMvc.perform(get(JWKS_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void jwksResponseNeverContainsThePrivateKeyExponent() throws Exception {
        String body = mockMvc.perform(get(JWKS_PATH))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"d\"");
    }

    @Test
    void tokenSignedByJwtSignerCanBeVerifiedWithThePublicKeyPublishedInJwks() throws Exception {
        JwtSigner jwtSigner = new JwtSigner(keyProvider, 60);
        UUID userId = UUID.randomUUID();

        String token = jwtSigner.sign(userId, RolUsuario.CONDUCTOR, null, Duration.ofMinutes(5));

        String body = mockMvc.perform(get(JWKS_PATH))
                .andReturn().getResponse().getContentAsString();

        JWKSet jwkSet = JWKSet.parse(body);
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);
        PublicKey publicKeyFromJwks = rsaKey.toPublicKey();

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(publicKeyFromJwks)
                .build()
                .parseSignedClaims(token);

        assertThat(parsed.getPayload().getSubject()).isEqualTo(userId.toString());
    }
}
