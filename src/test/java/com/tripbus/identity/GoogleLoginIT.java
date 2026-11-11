package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GoogleFalso;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login con Google contra PostgreSQL y Redis reales (Testcontainers): la cuenta sin contraseña y el
 * google_sub único de V13, y el bloqueo por intentos (Redis) también frena /auth/google. El ID token lo
 * firma {@link GoogleFalso} y su JWKS se sirve en local: no se llama a Google.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tripbus.events.enabled=false")
@ActiveProfiles("local")
class GoogleLoginIT extends IntegrationContainers {

    private static final GoogleFalso GOOGLE = new GoogleFalso();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) throws IOException {
        String jwksUri = GOOGLE.iniciarServidor();
        registry.add("tripbus.identity.google.client-id", () -> GoogleFalso.CLIENT_ID);
        registry.add("tripbus.identity.google.jwks-uri", () -> jwksUri);
    }

    @AfterAll
    static void apagar() {
        GOOGLE.close();
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private ConsentimientoRepository consentimientos;

    @Test
    void registroConGoogleGuardaUnaCuentaSinContrasenaYLaSegundaVezEntraPorElSub() {
        String email = "google.it." + UUID.randomUUID() + "@gmail.com";
        String sub = "sub-" + UUID.randomUUID();

        ResponseEntity<JsonNode> registro = rest.postForEntity("/auth/google", Map.of(
                "idToken", GOOGLE.token(sub, email, "Prueba Google"),
                "aceptaPoliticaDatos", true,
                "documentoIdentidad", "CC-G-" + UUID.randomUUID()), JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Usuario creado = usuarios.findByGoogleSub(sub).orElseThrow();
        assertThat(creado.getPasswordHash()).isNull();
        assertThat(creado.isEmailVerificado()).isTrue();
        assertThat(consentimientos.findByUsuarioId(creado.getId())).hasSize(1);

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/google",
                Map.of("idToken", GOOGLE.token(sub, email, "Prueba Google")), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("userId").asText()).isEqualTo(creado.getId().toString());
        // Una sola descarga del JWKS: la segunda verificación usa la caché (Cache-Control max-age).
        assertThat(GOOGLE.descargas()).isEqualTo(1);
    }

    @Test
    void cuentaBloqueadaPorIntentosFallidosTambienSeBloqueaConGoogle() {
        String email = "bloqueo.google." + UUID.randomUUID() + "@tripbus.co";
        ResponseEntity<JsonNode> registro = rest.postForEntity("/auth/register", Map.of(
                "email", email,
                "password", "Passw0rd1",
                "documentoIdentidad", "CC-B-" + UUID.randomUUID(),
                "nombre", "Bloqueo Google",
                "aceptaPoliticaDatos", true), JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (int i = 0; i < 5; i++) {
            rest.postForEntity("/auth/login", Map.of("email", email, "password", "Incorrecta1"), JsonNode.class);
        }

        ResponseEntity<JsonNode> google = rest.postForEntity("/auth/google",
                Map.of("idToken", GOOGLE.token("sub-" + UUID.randomUUID(), email, "Bloqueo")), JsonNode.class);

        assertThat(google.getStatusCode().value()).isEqualTo(423);
        assertThat(google.getBody().get("error").get("code").asText()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(google.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(usuarios.findByEmail(email).orElseThrow().getGoogleSub()).isNull();
    }
}
