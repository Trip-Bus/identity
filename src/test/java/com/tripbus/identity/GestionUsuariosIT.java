package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tripbus.identity.model.Consentimiento;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.PasswordResetTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-101 a US-104 contra PostgreSQL y Redis reales (Testcontainers, requiere Docker). Catalog se
 * simula con un servidor HTTP real que exige el X-Internal-Token, así se prueba la llamada completa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"tripbus.events.enabled=true", "tripbus.internal-token=token-it"})
@ActiveProfiles("local")
@Testcontainers
class GestionUsuariosIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** companyId → estado que responde el catalog simulado; si no está, 404. */
    private static final Map<String, String> EMPRESAS = new ConcurrentHashMap<>();
    private static final HttpServer CATALOG = iniciarCatalog();

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry registry) {
        registry.add("tripbus.services.catalog-url", () -> "http://127.0.0.1:" + CATALOG.getAddress().getPort());
    }

    @AfterAll
    static void detenerCatalog() {
        CATALOG.stop(0);
    }

    private static HttpServer iniciarCatalog() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/companies/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/internal/companies/".length());
                String estado = EMPRESAS.get(id);
                int status;
                String cuerpo;
                if (!"token-it".equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                    status = 401;
                    cuerpo = "{}";
                } else if (estado == null) {
                    status = 404;
                    cuerpo = "{\"error\":{\"code\":\"COMPANY_NOT_FOUND\"}}";
                } else {
                    status = 200;
                    cuerpo = "{\"id\":\"%s\",\"nombre\":\"Empresa\",\"estado\":\"%s\"}".formatted(id, estado);
                }
                byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ConsentimientoRepository consentimientoRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private JwtSigner jwtSigner;

    private final UUID activa = UUID.randomUUID();
    private final UUID inactiva = UUID.randomUUID();
    private Usuario admin;

    @BeforeEach
    void setUp() {
        // TestRestTemplate por defecto no soporta PATCH: se usa el cliente del JDK con PATCH habilitado.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        EMPRESAS.put(activa.toString(), "ACTIVA");
        EMPRESAS.put(inactiva.toString(), "INACTIVA");
        admin = new Usuario("admin." + UUID.randomUUID() + "@tripbus.co", "hash", "CC-" + UUID.randomUUID(), "Admin IT");
        admin.setRol(RolUsuario.ADMIN);
        admin = usuarioRepository.save(admin);
    }

    private HttpHeaders auth(Usuario u) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtSigner.sign(u.getId(), u.getRol(), u.getCompanyId(), u.getEmail(), Duration.ofMinutes(5)));
        return headers;
    }

    private ResponseEntity<JsonNode> crear(String email, String documento, String nombre, UUID empresa) {
        Map<String, Object> body = Map.of("nombre", nombre, "email", email, "documentoIdentidad", documento,
                "rol", "OPERADOR", "companyId", empresa);
        return rest.exchange("/users", HttpMethod.POST, new HttpEntity<>(body, auth(admin)), JsonNode.class);
    }

    private List<MapRecord<String, Object, Object>> eventos(String tipo) {
        return redis.opsForStream().read(StreamOffset.<String>fromStart("tripbus:events")).stream()
                .filter(e -> tipo.equals(e.getValue().get("type")))
                .toList();
    }

    @Test
    void adminCreaOperadorValidadoEnCatalogYLoEncuentraBuscandoSinTildes() throws Exception {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<JsonNode> creado = crear("Lucia." + sufijo + "@TripBus.co", "CC-" + sufijo,
                "Lucía Fernández " + sufijo, activa);
        assertThat(creado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(creado.getBody().get("id").asText());
        assertThat(creado.getBody().get("email").asText()).isEqualTo("lucia." + sufijo + "@tripbus.co");

        assertThat(consentimientoRepository.findByUsuarioId(id)).singleElement()
                .satisfies(c -> assertThat(c.getOrigen()).isEqualTo(Consentimiento.ORIGEN_ADMIN));
        assertThat(passwordResetTokenRepository.findAll()).anySatisfy(t -> assertThat(t.getUsuarioId()).isEqualTo(id));
        assertThat(eventos("UserRegistered")).anySatisfy(e -> assertThat((String) e.getValue().get("payload"))
                .contains(id.toString()));

        ResponseEntity<JsonNode> busqueda = rest.exchange("/users?q=LUCIA fernandez " + sufijo + "&rol=OPERADOR",
                HttpMethod.GET, new HttpEntity<>(auth(admin)), JsonNode.class);
        assertThat(busqueda.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(busqueda.getBody().get("totalElements").asLong()).isEqualTo(1);
        assertThat(busqueda.getBody().at("/content/0/id").asText()).isEqualTo(id.toString());
        assertThat(busqueda.getBody().toString()).doesNotContain("passwordHash");
    }

    @Test
    void empresaInexistenteOInactivaEnCatalogDa400YNoCreaNada() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<JsonNode> noExiste = crear("x" + sufijo + "@tripbus.co", "CC-X" + sufijo, "X", UUID.randomUUID());
        assertThat(noExiste.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noExiste.getBody().at("/error/code").asText()).isEqualTo("COMPANY_NOT_FOUND");

        ResponseEntity<JsonNode> inactivaResp = crear("x" + sufijo + "@tripbus.co", "CC-X" + sufijo, "X", inactiva);
        assertThat(inactivaResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inactivaResp.getBody().at("/error/code").asText()).isEqualTo("COMPANY_INACTIVE");

        assertThat(usuarioRepository.existsByEmail("x" + sufijo + "@tripbus.co")).isFalse();
    }

    @Test
    void suspenderDejaLaClaveEnRedisPublicaElEventoYRechazaElTokenReactivarLoRevierte() throws Exception {
        Usuario conductor = new Usuario("cond." + UUID.randomUUID() + "@tripbus.co", "hash",
                "CC-" + UUID.randomUUID(), "Conductor IT");
        conductor.setRol(RolUsuario.CONDUCTOR);
        conductor.setCompanyId(activa);
        conductor = usuarioRepository.save(conductor);
        HttpHeaders tokenConductor = auth(conductor);
        String clave = "tripbus:suspended:" + conductor.getId();

        ResponseEntity<JsonNode> suspendido = rest.exchange("/users/" + conductor.getId() + "/estado", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("activo", false, "motivo", "Documentos vencidos"), auth(admin)), JsonNode.class);
        assertThat(suspendido.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspendido.getBody().get("activo").asBoolean()).isFalse();
        assertThat(redis.opsForValue().get(clave)).isEqualTo("1");
        assertThat(redis.getExpire(clave)).isEqualTo(-1L);
        UUID conductorId = conductor.getId();
        JsonNode payload = objectMapper.readTree((String) eventos("UserSuspended").stream()
                .filter(e -> ((String) e.getValue().get("payload")).contains(conductorId.toString()))
                .findFirst().orElseThrow().getValue().get("payload"));
        assertThat(payload.get("motivo").asText()).isEqualTo("Documentos vencidos");

        ResponseEntity<JsonNode> validar = rest.exchange("/auth/validate", HttpMethod.GET,
                new HttpEntity<>(tokenConductor), JsonNode.class);
        assertThat(validar.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(validar.getBody().at("/error/code").asText()).isEqualTo("ACCOUNT_SUSPENDED");

        ResponseEntity<JsonNode> reactivado = rest.exchange("/users/" + conductor.getId() + "/estado", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("activo", true), auth(admin)), JsonNode.class);
        assertThat(reactivado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redis.hasKey(clave)).isFalse();
        assertThat(eventos("UserReactivated")).anySatisfy(e -> assertThat((String) e.getValue().get("payload"))
                .contains(conductorId.toString()));
        assertThat(rest.exchange("/auth/validate", HttpMethod.GET, new HttpEntity<>(tokenConductor), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cambiarRolAOperadorValidaLaEmpresaEnCatalog() {
        Usuario pasajero = new Usuario("pas." + UUID.randomUUID() + "@tripbus.co", "hash",
                "CC-" + UUID.randomUUID(), "Pasajero IT");
        pasajero = usuarioRepository.save(pasajero);

        ResponseEntity<JsonNode> rechazado = rest.exchange("/users/" + pasajero.getId() + "/role", HttpMethod.PUT,
                new HttpEntity<>(Map.of("rol", "OPERADOR", "companyId", inactiva), auth(admin)), JsonNode.class);
        assertThat(rechazado.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rechazado.getBody().at("/error/code").asText()).isEqualTo("COMPANY_INACTIVE");

        ResponseEntity<JsonNode> aceptado = rest.exchange("/users/" + pasajero.getId() + "/role", HttpMethod.PUT,
                new HttpEntity<>(Map.of("rol", "OPERADOR", "companyId", activa), auth(admin)), JsonNode.class);
        assertThat(aceptado.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aceptado.getBody().get("companyId").asText()).isEqualTo(activa.toString());
    }
}
