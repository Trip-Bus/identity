package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-131, US-105 y US-106 contra PostgreSQL y Redis reales (Testcontainers, requiere Docker): migraciones
 * V10 a V12 (jsonb, llaves foráneas, bloqueos FOR UPDATE), la clave tripbus:suspended y el evento UserDeleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tripbus.events.enabled=true")
@ActiveProfiles("local")
class DatosAuditoriaYDosPasosIT extends IntegrationContainers {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private VerificacionDosPasosRepository verificaciones;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private JwtSigner jwtSigner;

    private Usuario admin;

    @BeforeEach
    void setUp() {
        // TestRestTemplate por defecto no soporta PATCH: se usa el cliente del JDK.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        String sufijo = UUID.randomUUID().toString();
        admin = new Usuario("it.admin." + sufijo + "@tripbus.co", passwordHasher.hash(PASSWORD), "CC-" + sufijo,
                "Admin IT");
        admin.setRol(RolUsuario.ADMIN);
        admin = usuarioRepository.saveAndFlush(admin);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders auth(Usuario u) {
        return bearer(jwtSigner.sign(u.getId(), u.getRol(), u.getCompanyId(), u.getEmail(), false, Duration.ofMinutes(5)));
    }

    private ResponseEntity<JsonNode> exchange(String ruta, HttpMethod metodo, Object body, HttpHeaders headers) {
        return rest.exchange(ruta, metodo, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private List<MapRecord<String, Object, Object>> eventos(String tipo) {
        return redis.opsForStream().read(StreamOffset.<String>fromStart("tripbus:events")).stream()
                .filter(e -> tipo.equals(e.getValue().get("type")))
                .toList();
    }

    @Test
    void exportarYEliminarMisDatosConRedisYAuditoriaEnJsonb() throws Exception {
        ResponseEntity<JsonNode> registro = rest.postForEntity("/auth/register", Map.of(
                "email", "it.datos." + UUID.randomUUID() + "@tripbus.co",
                "password", PASSWORD,
                "documentoIdentidad", "CC-" + UUID.randomUUID(),
                "nombre", "Persona IT",
                "aceptaPoliticaDatos", true), JsonNode.class);
        assertThat(registro.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders token = bearer(registro.getBody().get("accessToken").asText());
        String userId = registro.getBody().get("userId").asText();

        ResponseEntity<JsonNode> datos = exchange("/users/me/data", HttpMethod.GET, null, token);
        assertThat(datos.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(datos.getBody().at("/consentimientos/0/politica").asText()).isEqualTo("POLITICA_TRATAMIENTO_DATOS");
        assertThat(datos.getBody().get("sesionesActivas")).hasSize(1);

        ResponseEntity<JsonNode> eliminado = exchange("/users/me/delete", HttpMethod.POST,
                Map.of("password", PASSWORD, "confirmacion", "ELIMINAR"), token);
        assertThat(eliminado.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(redis.opsForValue().get("tripbus:suspended:" + userId)).isEqualTo("1");
        JsonNode payload = objectMapper.readTree((String) eventos("UserDeleted").stream()
                .filter(e -> ((String) e.getValue().get("payload")).contains(userId))
                .findFirst().orElseThrow().getValue().get("payload"));
        assertThat(payload.get("userId").asText()).isEqualTo(userId);
        assertThat(exchange("/users/me", HttpMethod.GET, null, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> auditoria = exchange("/admin/audit?accion=ELIMINAR_CUENTA&targetUserId=" + userId,
                HttpMethod.GET, null, auth(admin));
        assertThat(auditoria.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditoria.getBody().at("/content/0/actorId").asText()).isEqualTo(userId);
        assertThat(auditoria.getBody().at("/content/0/despues/anonimizada").asBoolean()).isTrue();
        assertThat(auditoria.getBody().at("/content/0/antes/rol").asText()).isEqualTo("PASAJERO");
    }

    @Test
    void suspenderQuedaAuditadoConElMotivo() {
        Usuario pasajero = usuarioRepository.saveAndFlush(new Usuario("it.p." + UUID.randomUUID() + "@tripbus.co",
                "hash", "CC-" + UUID.randomUUID(), "Pasajero IT"));

        ResponseEntity<JsonNode> suspendido = exchange("/users/" + pasajero.getId() + "/estado", HttpMethod.PATCH,
                Map.of("activo", false, "motivo", "Fraude"), auth(admin));
        assertThat(suspendido.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> auditoria = exchange("/admin/audit?actorId=" + admin.getId(), HttpMethod.GET, null,
                auth(admin));
        assertThat(auditoria.getBody().get("totalElements").asLong()).isEqualTo(1);
        JsonNode registro = auditoria.getBody().at("/content/0");
        assertThat(registro.get("accion").asText()).isEqualTo("SUSPENDER");
        assertThat(registro.get("motivo").asText()).isEqualTo("Fraude");
        assertThat(registro.at("/antes/activo").asBoolean()).isTrue();
        assertThat(registro.at("/despues/activo").asBoolean()).isFalse();
    }

    @Test
    void loginEnDosPasosConPostgres() {
        HttpHeaders tokenAdmin = auth(admin);
        ResponseEntity<JsonNode> setup = exchange("/users/me/2fa/setup", HttpMethod.POST, null, tokenAdmin);
        assertThat(setup.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] secreto = cifrador.descifrar(verificaciones.findById(admin.getId()).orElseThrow().getSecretoCifrado(),
                admin.getId().toString());
        long paso = Totp.paso(Instant.now());
        ResponseEntity<JsonNode> enable = exchange("/users/me/2fa/enable", HttpMethod.POST,
                Map.of("code", Totp.codigo(secreto, paso)), tokenAdmin);
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enable.getBody().get("recoveryCodes")).hasSize(10);

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login",
                Map.of("email", admin.getEmail(), "password", PASSWORD), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("mfaRequired").asBoolean()).isTrue();
        assertThat(login.getBody().has("accessToken")).isFalse();

        Map<String, Object> segundoPaso = new HashMap<>();
        segundoPaso.put("mfaToken", login.getBody().get("mfaToken").asText());
        segundoPaso.put("code", Totp.codigo(secreto, paso + 1));
        ResponseEntity<JsonNode> tokens = rest.postForEntity("/auth/2fa", segundoPaso, JsonNode.class);
        assertThat(tokens.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokens.getBody().get("accessToken").asText()).isNotBlank();
        assertThat(eventos("UserLoggedIn")).anySatisfy(e -> assertThat((String) e.getValue().get("payload"))
                .contains(admin.getId().toString()));

        ResponseEntity<JsonNode> otraVez = rest.postForEntity("/auth/login",
                Map.of("email", admin.getEmail(), "password", PASSWORD), JsonNode.class);
        segundoPaso.put("mfaToken", otraVez.getBody().get("mfaToken").asText());
        ResponseEntity<JsonNode> repetido = rest.postForEntity("/auth/2fa", segundoPaso, JsonNode.class);
        assertThat(repetido.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(repetido.getBody().at("/error/code").asText()).isEqualTo("INVALID_MFA_CODE");
    }
}
