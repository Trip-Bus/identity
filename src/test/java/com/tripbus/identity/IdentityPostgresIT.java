package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.identity.repository.ConsentimientoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integración contra PostgreSQL 16 real (Testcontainers, requiere Docker): Flyway aplica las
 * migraciones REALES de db/migration (las pruebas con H2 las tienen apagadas) y Hibernate valida
 * el esquema (ddl-auto: validate). Perfil "local": llaves JWT efímeras sin heredar el H2 de "test".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tripbus.events.enabled=false")
@ActiveProfiles("local")
@Testcontainers
class IdentityPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ConsentimientoRepository consentimientoRepository;

    @Test
    void registroYLoginContraPostgresReal() {
        ResponseEntity<JsonNode> registro = rest.postForEntity("/auth/register", Map.of(
                "email", "it.postgres@tripbus.co",
                "password", "Passw0rd1",
                "documentoIdentidad", "CC-9901",
                "nombre", "Prueba IT",
                "aceptaPoliticaDatos", true), JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = UUID.fromString(registro.getBody().get("userId").asText());

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login", Map.of(
                "email", "it.postgres@tripbus.co",
                "password", "Passw0rd1"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("accessToken").asText()).isNotBlank();

        assertThat(consentimientoRepository.findByUsuarioId(userId)).hasSize(1);
    }
}
