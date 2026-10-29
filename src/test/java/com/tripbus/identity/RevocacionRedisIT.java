package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-006 contra PostgreSQL y Redis reales (Testcontainers, requiere Docker): el logout deja la
 * clave tripbus:revoked:{jti} con el TTL que le quedaba al token y publica SessionRevoked en el
 * stream de eventos, que es lo que lee el Realtime Gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tripbus.events.enabled=true")
@ActiveProfiles("local")
@Testcontainers
class RevocacionRedisIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logoutGuardaLaClaveConElTtlRestanteYPublicaSessionRevoked() throws Exception {
        ResponseEntity<JsonNode> registro = rest.postForEntity("/auth/register", Map.of(
                "email", "revocado." + UUID.randomUUID() + "@tripbus.co",
                "password", "Passw0rd1",
                "documentoIdentidad", "CC-" + UUID.randomUUID(),
                "nombre", "Prueba Revocación",
                "aceptaPoliticaDatos", true), JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = registro.getBody().get("accessToken").asText();
        String userId = registro.getBody().get("userId").asText();

        JsonNode claims = objectMapper.readTree(
                new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
        String jti = claims.get("jti").asText();
        long restanteSegundos = claims.get("exp").asLong() - Instant.now().getEpochSecond();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Void> logout =
                rest.exchange("/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String clave = "tripbus:revoked:" + jti;
        assertThat(redis.opsForValue().get(clave)).isEqualTo("1");
        Long ttl = redis.getExpire(clave, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(restanteSegundos - 5, restanteSegundos + 1);

        List<MapRecord<String, Object, Object>> eventos =
                redis.opsForStream().read(StreamOffset.fromStart("tripbus:events"));
        MapRecord<String, Object, Object> revocado = eventos.stream()
                .filter(e -> "SessionRevoked".equals(e.getValue().get("type")))
                .findFirst().orElseThrow();
        assertThat(revocado.getValue()).containsEntry("source", "identity");
        JsonNode payload = objectMapper.readTree((String) revocado.getValue().get("payload"));
        assertThat(payload.get("jti").asText()).isEqualTo(jti);
        assertThat(payload.get("userId").asText()).isEqualTo(userId);
    }

    @Test
    void quintoFalloBloqueaIgualUnEmailRegistradoYUnoInexistente() {
        String existente="lock."+UUID.randomUUID()+"@tripbus.co";
        ResponseEntity<JsonNode> registro=rest.postForEntity("/auth/register",Map.of(
                "email",existente,"password","Passw0rd1","documentoIdentidad","CC-"+UUID.randomUUID(),
                "nombre","Cuenta bloqueable","aceptaPoliticaDatos",true),JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> cuenta=fallarCincoVeces(existente);
        ResponseEntity<JsonNode> desconocido=fallarCincoVeces("missing."+UUID.randomUUID()+"@tripbus.co");
        assertThat(cuenta.getStatusCode().value()).isEqualTo(423);
        assertThat(desconocido.getStatusCode().value()).isEqualTo(423);
        assertThat(cuenta.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(desconocido.getBody().at("/error/code").asText()).isEqualTo("ACCOUNT_LOCKED");
        ResponseEntity<JsonNode> contrasenaCorrecta=rest.postForEntity("/auth/login",Map.of(
                "email",existente,"password","Passw0rd1"),JsonNode.class);
        assertThat(contrasenaCorrecta.getStatusCode().value()).isEqualTo(423);
    }

    private ResponseEntity<JsonNode> fallarCincoVeces(String email) {
        ResponseEntity<JsonNode> respuesta=null;
        for(int i=0;i<5;i++) respuesta=rest.postForEntity("/auth/login",Map.of("email",email,"password","WrongPass1"),JsonNode.class);
        return respuesta;
    }
}
