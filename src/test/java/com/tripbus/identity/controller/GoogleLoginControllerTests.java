package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.tripbus.identity.observabilidad.MetricasIdentity;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.model.VerificacionDosPasos;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.GoogleFalso;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.security.Totp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /auth/google y GET /auth/providers de punta a punta con H2: los ID token los firma {@link GoogleFalso}
 * y el JWKS lo sirve un servidor HTTP local (GOOGLE_JWKS_URI). Ninguna prueba llama a Google.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleLoginControllerTests {

    private static final GoogleFalso GOOGLE = new GoogleFalso();
    private static final String JWKS_URI;

    static {
        try {
            JWKS_URI = GOOGLE.iniciarServidor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        registry.add("tripbus.identity.google.client-id", () -> GoogleFalso.CLIENT_ID);
        registry.add("tripbus.identity.google.jwks-uri", () -> JWKS_URI);
    }

    @AfterAll
    static void apagar() {
        GOOGLE.close();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private ConsentimientoRepository consentimientos;
    @Autowired
    private VerificacionDosPasosRepository verificaciones;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private MeterRegistry meterRegistry;

    private static String unico() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions google(Object body) throws Exception {
        return mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions google(String idToken) throws Exception {
        return google(Map.of("idToken", idToken));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private Usuario guardar(String email, String googleSub, boolean verificado) {
        Usuario u = new Usuario(email, passwordHasher.hash("Passw0rd1"), "CC-" + unico(), "Persona Google");
        u.setGoogleSub(googleSub);
        u.setEmailVerificado(verificado);
        return usuarios.saveAndFlush(u);
    }

    @Test
    void providersAnunciaGoogleConElClientId() throws Exception {
        mockMvc.perform(get("/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google.enabled").value(true))
                .andExpect(jsonPath("$.google.clientId").value(GoogleFalso.CLIENT_ID));
    }

    @Test
    void usuarioExistentePorSubIniciaSesion() throws Exception {
        String sub = "sub-" + unico();
        Usuario existente = guardar("otro.correo." + unico() + "@tripbus.co", sub, true);

        JsonNode respuesta = leer(google(GOOGLE.token(sub, "cambio." + unico() + "@gmail.com", "Ana"))
                .andExpect(status().isOk()));

        assertThat(respuesta.get("userId").asText()).isEqualTo(existente.getId().toString());
        assertThat(respuesta.get("accessToken").asText()).isNotBlank();
        assertThat(respuesta.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    void usuarioConElMismoCorreoSeVinculaYQuedaVerificado() throws Exception {
        String email = "vincular." + unico() + "@tripbus.co";
        Usuario existente = guardar(email, null, false);
        String sub = "sub-" + unico();

        JsonNode respuesta = leer(google(GOOGLE.token(sub, email.toUpperCase(), "Ana")).andExpect(status().isOk()));

        Usuario vinculado = usuarios.findById(existente.getId()).orElseThrow();
        assertThat(vinculado.getGoogleSub()).isEqualTo(sub);
        assertThat(vinculado.isEmailVerificado()).isTrue();
        assertThat(SignedJWT.parse(respuesta.get("accessToken").asText()).getJWTClaimsSet()
                .getBooleanClaim("emailVerificado")).isTrue();
        // La contraseña de antes sigue sirviendo.
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Passw0rd1"))))
                .andExpect(status().isOk());
    }

    @Test
    void sinCuentaNiDatosDeRegistroResponde422ConEmailYNombreDelToken() throws Exception {
        String email = "nuevo." + unico() + "@gmail.com";

        google(GOOGLE.token("sub-" + unico(), email, "Ana Gómez"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_REGISTRO_INCOMPLETO"))
                .andExpect(jsonPath("$.error.details.email").value(email))
                .andExpect(jsonPath("$.error.details.nombre").value("Ana Gómez"));
        assertThat(usuarios.findByEmail(email)).isEmpty();
    }

    @Test
    void registroCompletoCreaUnPasajeroSinContrasenaConConsentimiento() throws Exception {
        String email = "registro." + unico() + "@gmail.com";
        String sub = "sub-" + unico();
        Map<String, Object> body = new HashMap<>();
        body.put("idToken", GOOGLE.token(sub, email, "Ana Gómez"));
        body.put("aceptaPoliticaDatos", true);
        body.put("documentoIdentidad", "CC-" + unico());

        JsonNode respuesta = leer(google(body).andExpect(status().isCreated()));

        Usuario creado = usuarios.findByGoogleSub(sub).orElseThrow();
        assertThat(respuesta.get("userId").asText()).isEqualTo(creado.getId().toString());
        assertThat(respuesta.get("rol").asText()).isEqualTo("PASAJERO");
        assertThat(creado.getRol()).isEqualTo(RolUsuario.PASAJERO);
        assertThat(creado.getPasswordHash()).isNull();
        assertThat(creado.isEmailVerificado()).isTrue();
        assertThat(creado.getNombre()).isEqualTo("Ana Gómez");
        assertThat(consentimientos.findByUsuarioId(creado.getId())).hasSize(1);

        // Sin contraseña, el login normal responde igual que unas credenciales inválidas.
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "Passw0rd1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        // La segunda vez ya entra por el sub, sin datos de registro.
        google(GOOGLE.token(sub, email, "Ana Gómez")).andExpect(status().isOk());

        assertThat(meterRegistry.get(MetricasIdentity.REGISTROS).tag(MetricasIdentity.TAG_PROVEEDOR, "google")
                .counter().count()).isPositive();
        assertThat(meterRegistry.get(MetricasIdentity.LOGINS).tag(MetricasIdentity.TAG_PROVEEDOR, "google")
                .tag(MetricasIdentity.TAG_RESULTADO, "exito").counter().count()).isPositive();
    }

    @Test
    void conDosPasosActivaDevuelveElDesafioYSeCompletaEnAuth2fa() throws Exception {
        String sub = "sub-" + unico();
        Usuario existente = guardar("mfa." + unico() + "@tripbus.co", sub, true);
        byte[] secreto = Totp.nuevoSecreto();
        VerificacionDosPasos verificacion = new VerificacionDosPasos(existente.getId(),
                cifrador.cifrar(secreto, existente.getId().toString()), Instant.now());
        verificacion.setActiva(true);
        verificaciones.saveAndFlush(verificacion);

        JsonNode desafio = leer(google(GOOGLE.token(sub, existente.getEmail(), "Ana")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist()));

        mockMvc.perform(post("/auth/2fa").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", desafio.get("mfaToken").asText(),
                                "code", Totp.codigo(secreto, Totp.paso(Instant.now()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(existente.getId().toString()));
    }

    @Test
    void correoVinculadoAOtraCuentaDeGoogleResponde409() throws Exception {
        Usuario existente = guardar("vinculado." + unico() + "@tripbus.co", "sub-original-" + unico(), true);

        google(GOOGLE.token("sub-otro-" + unico(), existente.getEmail(), "Ana"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_ACCOUNT_CONFLICT"));
    }

    @Test
    void usuarioSuspendidoRecibeElMismoErrorQueEnElLogin() throws Exception {
        String sub = "sub-" + unico();
        Usuario existente = guardar("suspendido." + unico() + "@tripbus.co", sub, true);
        existente.setActivo(false);
        usuarios.saveAndFlush(existente);

        google(GOOGLE.token(sub, existente.getEmail(), "Ana"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void tokensInvalidosResponden401GoogleTokenInvalid() throws Exception {
        String email = "invalido." + unico() + "@gmail.com";
        String[] tokens = {
                GOOGLE.firmarConOtraLlave(GoogleFalso.claims("s", email, "Ana").build()),
                GOOGLE.firmar(GoogleFalso.claims("s", email, "Ana").audience("otra-app").build()),
                GOOGLE.firmar(GoogleFalso.claims("s", email, "Ana")
                        .expirationTime(Date.from(Instant.now().minusSeconds(120))).build()),
                GOOGLE.firmar(GoogleFalso.claims("s", email, "Ana").claim("email_verified", false).build()),
                "no-es-un-jwt"
        };
        for (String token : tokens) {
            google(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("GOOGLE_TOKEN_INVALID"));
        }
        assertThat(usuarios.findByEmail(email)).isEmpty();
    }

    @Test
    void sinIdTokenResponde400() throws Exception {
        google(Map.of("idToken", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void losDosEndpointsSalenEnElOpenApi() throws Exception {
        String openApi = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(openApi).contains("\"/auth/google\"", "\"/auth/providers\"", "GOOGLE_REGISTRO_INCOMPLETO");
    }
}
