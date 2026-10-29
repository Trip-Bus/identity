package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripbus.identity.repository.ConsentimientoRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private ResponseEntity<JsonNode> registrar(String email, String documento) {
        return rest.postForEntity("/auth/register", Map.of(
                "email", email,
                "password", "Passw0rd1",
                "documentoIdentidad", documento,
                "nombre", "Prueba IT",
                "aceptaPoliticaDatos", true), JsonNode.class);
    }

    /**
     * Dos registros simultáneos con el mismo email (en distinto caso de letras): ambos pasan el
     * existsByEmail antes de que el otro confirme (el hash Argon2 va entre la verificación y el
     * INSERT), así que el segundo choca con el índice único lower(email) y debe responder 409.
     */
    @Test
    void dosRegistrosConcurrentesConElMismoEmailDanUn201YUn409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int ronda = 0; ronda < 3; ronda++) {
                String email = "carrera" + ronda + "@tripbus.co";
                CyclicBarrier salida = new CyclicBarrier(2);
                Callable<ResponseEntity<JsonNode>> a = () -> {
                    salida.await();
                    return registrar(email, "CC-A" + UUID.randomUUID());
                };
                Callable<ResponseEntity<JsonNode>> b = () -> {
                    salida.await();
                    return registrar(email.toUpperCase(Locale.ROOT), "CC-B" + UUID.randomUUID());
                };
                List<ResponseEntity<JsonNode>> respuestas = new ArrayList<>();
                for (Future<ResponseEntity<JsonNode>> f : pool.invokeAll(List.of(a, b))) {
                    respuestas.add(f.get(60, TimeUnit.SECONDS));
                }

                assertThat(respuestas).extracting(ResponseEntity::getStatusCode)
                        .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
                JsonNode conflicto = respuestas.stream()
                        .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow().getBody();
                assertThat(conflicto.at("/error/code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dosRegistrosConcurrentesConElMismoDocumentoDanUn201YUn409() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier salida = new CyclicBarrier(2);
            String documento = "CC-" + UUID.randomUUID();
            List<Callable<ResponseEntity<JsonNode>>> tareas = List.of(
                    () -> { salida.await(); return registrar("doc.a@tripbus.co", documento); },
                    () -> { salida.await(); return registrar("doc.b@tripbus.co", documento); });
            List<ResponseEntity<JsonNode>> respuestas = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : pool.invokeAll(tareas)) {
                respuestas.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(respuestas).extracting(ResponseEntity::getStatusCode)
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            JsonNode conflicto = respuestas.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow().getBody();
            assertThat(conflicto.at("/error/code").asText()).isEqualTo("DOCUMENTO_ALREADY_REGISTERED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void registroConMayusculasYEspaciosYLoginEnMinusculasContraPostgresReal() {
        assertThat(registrar("Ana.IT@Mail.com ", "CC-ANA-IT").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login", Map.of(
                "email", "ana.it@mail.com",
                "password", "Passw0rd1"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> duplicado = registrar("ANA.it@MAIL.com", "CC-ANA-IT-2");
        assertThat(duplicado.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicado.getBody().at("/error/code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    /** V5 sobre un esquema aparte, con datos previos: pasa los correos a minúsculas. */
    @Test
    void migracionV5PasaAMinusculasLosCorreosExistentes() throws Exception {
        String esquema = "v5_ok";
        Flyway hastaV4 = flywayEnEsquema(esquema, "4");
        hastaV4.migrate();
        insertarUsuario(esquema, " Pepe@Mail.COM ", "CC-V5-1");

        flywayEnEsquema(esquema, "5").migrate();

        try (Connection c = conexion(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT email FROM " + esquema + ".usuarios")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("pepe@mail.com");
        }
        assertThatThrownBy(() -> insertarUsuario(esquema, "PEPE@mail.com", "CC-V5-2"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_usuarios_email_lower");
    }

    /** Si dos correos solo difieren en mayúsculas, V5 falla con un mensaje claro y no toca datos. */
    @Test
    void migracionV5FallaConMensajeClaroSiHayCorreosQueSoloDifierenEnMayusculas() throws Exception {
        String esquema = "v5_duplicados";
        flywayEnEsquema(esquema, "4").migrate();
        insertarUsuario(esquema, "Luis@Mail.com", "CC-V5-3");
        insertarUsuario(esquema, "luis@mail.com", "CC-V5-4");

        assertThatThrownBy(() -> flywayEnEsquema(esquema, "5").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("luis@mail.com (2 cuentas)")
                .hasMessageContaining("No se modificó ningún dato");

        try (Connection c = conexion(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT email FROM " + esquema + ".usuarios ORDER BY email")) {
            List<String> emails = new ArrayList<>();
            while (rs.next()) {
                emails.add(rs.getString(1));
            }
            assertThat(emails).containsExactly("Luis@Mail.com", "luis@mail.com");
        }
    }

    /** V6 rellena la columna de búsqueda de los usuarios existentes sin tildes ni mayúsculas. */
    @Test
    void migracionV6RellenaLaBusquedaDeLosUsuariosExistentes() throws Exception {
        String esquema = "v6_busqueda";
        flywayEnEsquema(esquema, "5").migrate();
        try (Connection c = conexion(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + esquema + ".usuarios (id, email, password_hash, documento_identidad, nombre,"
                        + " fecha_creacion, fecha_actualizacion) VALUES (?, 'jose@mail.com', 'hash', 'CC-V6',"
                        + " 'José ÑUÑEZ Álvarez', now(), now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.executeUpdate();
        }

        flywayEnEsquema(esquema, "6").migrate();

        try (Connection c = conexion(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT busqueda FROM " + esquema + ".usuarios")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("jose nunez alvarez jose@mail.com cc-v6");
        }
    }

    /** US-130: V8 deja verificados a los usuarios que ya existían (QA y demo) y los nuevos quedan sin verificar. */
    @Test
    void migracionV8DejaVerificadosALosUsuariosExistentes() throws Exception {
        String esquema = "v8_verificados";
        flywayEnEsquema(esquema, "5").migrate();
        insertarUsuario(esquema, "existente@mail.com", "CC-V8-1");

        flywayEnEsquema(esquema, "8").migrate();
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO " + esquema + ".usuarios (id, email, password_hash, documento_identidad,"
                    + " nombre, fecha_creacion, fecha_actualizacion, busqueda) VALUES ('" + UUID.randomUUID()
                    + "', 'nuevo@mail.com', 'hash', 'CC-V8-2', 'V8', now(), now(), 'v8')");
        }

        try (Connection c = conexion(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT email, email_verificado, email_verificado_en FROM " + esquema
                     + ".usuarios ORDER BY email")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("existente@mail.com");
            assertThat(rs.getBoolean(2)).isTrue();
            assertThat(rs.getTimestamp(3)).isNotNull();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("nuevo@mail.com");
            assertThat(rs.getBoolean(2)).isFalse();
            assertThat(rs.getTimestamp(3)).isNull();
        }
    }

    /** US-128: la BD también rechaza un teléfono que no sea un celular colombiano. */
    @Test
    void migracionV7SoloAceptaCelularesColombianos() throws Exception {
        String esquema = "v7_telefono";
        flywayEnEsquema(esquema, "5").migrate();
        insertarUsuario(esquema, "tel@mail.com", "CC-V7-1");
        flywayEnEsquema(esquema, "7").migrate();

        try (Connection c = conexion(); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE " + esquema + ".usuarios SET telefono = '3001234567'")).isEqualTo(1);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE " + esquema + ".usuarios SET telefono = '6011234567'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_usuarios_telefono");
        }
    }

    private static Flyway flywayEnEsquema(String esquema, String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(esquema)
                .locations("classpath:db/migration")
                .target(version)
                .load();
    }

    private static Connection conexion() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void insertarUsuario(String esquema, String email, String documento) throws SQLException {
        try (Connection c = conexion(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + esquema + ".usuarios (id, email, password_hash, documento_identidad, nombre,"
                        + " fecha_creacion, fecha_actualizacion) VALUES (?, ?, 'hash', ?, 'V5', now(), now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, email);
            ps.setString(3, documento);
            ps.executeUpdate();
        }
    }
}
