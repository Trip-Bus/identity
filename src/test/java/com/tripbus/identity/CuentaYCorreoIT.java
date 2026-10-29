package com.tripbus.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.tripbus.identity.repository.EmailVerificationTokenRepository;
import com.tripbus.identity.security.JwtKeyProvider;
import io.jsonwebtoken.Jwts;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-125, US-129 y US-130 contra PostgreSQL y Redis reales (Testcontainers) y un SMTP en memoria
 * (GreenMail): los correos salen de verdad por SMTP, en texto y HTML, con enlaces al frontend.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tripbus.events.enabled=true",
        "tripbus.mail.host=localhost",
        "tripbus.mail.port=3025",
        "tripbus.frontend-url=https://app.tripbus.test"})
@ActiveProfiles("local")
@Testcontainers
class CuentaYCorreoIT {

    private static final String PASSWORD = "Passw0rd1";
    private static final long ESPERA_CORREO_MS = 10_000;

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

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
    private JwtKeyProvider keyProvider;
    @Autowired
    private EmailVerificationTokenRepository verificationTokenRepository;

    private record Registro(String email, String userId, String token) {
    }

    private Registro registrar() {
        String email = "ana." + UUID.randomUUID() + "@tripbus.co";
        ResponseEntity<JsonNode> r = rest.postForEntity("/auth/register", Map.of(
                "email", email,
                "password", PASSWORD,
                "documentoIdentidad", "CC-" + UUID.randomUUID(),
                "nombre", "Ana Pérez",
                "aceptaPoliticaDatos", true), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Registro(email, r.getBody().get("userId").asText(), r.getBody().get("accessToken").asText());
    }

    private static HttpEntity<Object> conToken(String jwt, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(body, headers);
    }

    /** Espera (el envío es en segundo plano) el último correo que llegó a {@code email}. */
    private static MimeMessage correoPara(String email, int totalEsperado) throws Exception {
        assertThat(greenMail.waitForIncomingEmail(ESPERA_CORREO_MS, totalEsperado)).isTrue();
        MimeMessage[] recibidos = greenMail.getReceivedMessagesForDomain(email);
        assertThat(recibidos).isNotEmpty();
        return recibidos[recibidos.length - 1];
    }

    /** Contenido decodificado de la primera parte con ese tipo (el mensaje es multipart anidado). */
    private static String parte(Part parte, String tipo) throws Exception {
        if (parte.isMimeType(tipo)) {
            return (String) parte.getContent();
        }
        if (parte.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String encontrado = parte(multipart.getBodyPart(i), tipo);
                if (encontrado != null) {
                    return encontrado;
                }
            }
        }
        return null;
    }

    private static String tokenDelEnlace(String cuerpo, String ruta) {
        Matcher m = Pattern.compile(Pattern.quote("https://app.tripbus.test" + ruta + "?token=") + "([A-Za-z0-9_-]+)")
                .matcher(cuerpo);
        assertThat(m.find()).as("enlace %s en el correo", ruta).isTrue();
        return m.group(1);
    }

    @Test
    void alRegistrarseLlegaElCorreoDeVerificacionYConElEnlaceQuedaVerificado() throws Exception {
        Registro registro = registrar();

        MimeMessage correo = correoPara(registro.email(), 1);
        assertThat(correo.getSubject()).isEqualTo("Confirma tu correo en TripBus");
        assertThat(correo.getFrom()[0].toString()).contains("no-reply@tripbus.co");
        String cuerpo = parte(correo, "text/plain");
        assertThat(cuerpo).startsWith("Hola, Ana.").contains("Para confirmar que este correo es tuyo");
        assertThat(parte(correo, "text/html")).contains("<p>Hola, Ana.</p>", "href=\"https://app.tripbus.test/verificar-correo?token=");
        String token = tokenDelEnlace(cuerpo, "/verificar-correo");
        // En la BD solo queda el hash.
        assertThat(verificationTokenRepository.findAll()).noneMatch(t -> t.getTokenHash().equals(token));

        ResponseEntity<JsonNode> verificado = rest.postForEntity("/auth/verify-email", Map.of("token", token),
                JsonNode.class);
        assertThat(verificado.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> perfil = rest.exchange("/users/me", HttpMethod.GET, conToken(registro.token(), null),
                JsonNode.class);
        assertThat(perfil.getBody().get("emailVerificado").asBoolean()).isTrue();

        ResponseEntity<JsonNode> otraVez = rest.postForEntity("/auth/verify-email", Map.of("token", token),
                JsonNode.class);
        assertThat(otraVez.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(otraVez.getBody().at("/error/code").asText()).isEqualTo("TOKEN_INVALID_OR_EXPIRED");
    }

    @Test
    void laRecuperacionLlegaPorCorreoConElEnlaceARestablecerYFunciona() throws Exception {
        Registro registro = registrar();
        correoPara(registro.email(), 1);

        ResponseEntity<JsonNode> pedido = rest.postForEntity("/auth/password-reset/request",
                Map.of("email", registro.email()), JsonNode.class);
        assertThat(pedido.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        MimeMessage correo = correoPara(registro.email(), 2);
        assertThat(correo.getSubject()).isEqualTo("Crea una nueva contraseña en TripBus");
        String cuerpo = parte(correo, "text/plain");
        // SMTP entrega los saltos de línea como CRLF.
        assertThat(cuerpo).startsWith("Hola, Ana.").contains("Para crear una nueva contraseña");
        String token = tokenDelEnlace(cuerpo, "/restablecer");

        ResponseEntity<Void> confirmado = rest.postForEntity("/auth/password-reset/confirm",
                Map.of("token", token, "nuevaPassword", "NuevaPass1"), Void.class);
        assertThat(confirmado.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> login = rest.postForEntity("/auth/login",
                Map.of("email", registro.email(), "password", "NuevaPass1"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void pedirRecuperacionDeUnCorreoInexistenteRespondeIgualYNoEnviaNada() {
        ResponseEntity<JsonNode> pedido = rest.postForEntity("/auth/password-reset/request",
                Map.of("email", "nadie." + UUID.randomUUID() + "@tripbus.co"), JsonNode.class);

        assertThat(pedido.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(greenMail.waitForIncomingEmail(1_000, 1)).isFalse();
    }

    @Test
    void cambiarLaContrasenaDejaLaClaveEnRedisAvisaPorCorreoEInvalidaLosTokensAnteriores() throws Exception {
        Registro registro = registrar();
        correoPara(registro.email(), 1);
        Instant iatViejo = Instant.now().minusSeconds(30);
        String tokenViejo = Jwts.builder().header().keyId(keyProvider.getKeyId()).and()
                .id(UUID.randomUUID().toString())
                .subject(registro.userId())
                .claim("role", "PASAJERO")
                .issuedAt(Date.from(iatViejo))
                .expiration(Date.from(iatViejo.plus(Duration.ofMinutes(60))))
                .signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();

        long antes = Instant.now().getEpochSecond();
        ResponseEntity<JsonNode> cambio = rest.exchange("/users/me/password", HttpMethod.POST,
                conToken(registro.token(), Map.of("passwordActual", PASSWORD, "passwordNueva", "NuevaPass1")),
                JsonNode.class);
        assertThat(cambio.getStatusCode()).isEqualTo(HttpStatus.OK);
        String tokenNuevo = cambio.getBody().get("accessToken").asText();

        String clave = "tripbus:pwd-changed:" + registro.userId();
        assertThat(Long.parseLong(redis.opsForValue().get(clave))).isBetween(antes, Instant.now().getEpochSecond());
        assertThat(redis.getExpire(clave, TimeUnit.SECONDS)).isBetween(3_590L, 3_600L);

        MimeMessage aviso = correoPara(registro.email(), 2);
        assertThat(aviso.getSubject()).isEqualTo("Tu contraseña de TripBus cambió");
        assertThat(parte(aviso, "text/plain")).contains("https://app.tripbus.test/recuperar");

        ResponseEntity<JsonNode> viejo = rest.exchange("/auth/validate", HttpMethod.GET, conToken(tokenViejo, null),
                JsonNode.class);
        assertThat(viejo.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> nuevo = rest.exchange("/auth/validate", HttpMethod.GET, conToken(tokenNuevo, null),
                JsonNode.class);
        assertThat(nuevo.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cambiarConLaContrasenaActualIncorrectaNoTocaNada() {
        Registro registro = registrar();

        ResponseEntity<JsonNode> cambio = rest.exchange("/users/me/password", HttpMethod.POST,
                conToken(registro.token(), Map.of("passwordActual", "Incorrecta1", "passwordNueva", "NuevaPass1")),
                JsonNode.class);

        assertThat(cambio.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cambio.getBody().at("/error/code").asText()).isEqualTo("INVALID_CURRENT_PASSWORD");
        assertThat(redis.hasKey("tripbus:pwd-changed:" + registro.userId())).isFalse();
    }

    @Test
    void reenviarLaVerificacionMasDeTresVecesEnUnaHoraDevuelve429() throws Exception {
        Registro registro = registrar();
        for (int i = 0; i < 3; i++) {
            ResponseEntity<JsonNode> r = rest.exchange("/auth/verify-email/resend", HttpMethod.POST,
                    conToken(registro.token(), null), JsonNode.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<JsonNode> cuarta = rest.exchange("/auth/verify-email/resend", HttpMethod.POST,
                conToken(registro.token(), null), JsonNode.class);

        assertThat(cuarta.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(cuarta.getBody().at("/error/code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        // Registro + 3 reenvíos.
        assertThat(greenMail.waitForIncomingEmail(ESPERA_CORREO_MS, 4)).isTrue();
        assertThat(greenMail.getReceivedMessagesForDomain(registro.email())).hasSize(4);
    }
}
