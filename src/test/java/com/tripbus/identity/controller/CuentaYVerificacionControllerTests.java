package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.EmailVerificationToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.EmailVerificationTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtKeyProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-128, US-129 y US-130 de punta a punta con H2 (perfil test); el correo es un mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CuentaYVerificacionControllerTests {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EmailVerificationTokenRepository verificationTokenRepository;
    @Autowired
    private JwtKeyProvider keyProvider;

    @MockitoBean
    private EmailSender emailSender;

    private String email;
    private String token;
    private UUID userId;

    @BeforeEach
    void registrar() throws Exception {
        email = "cuenta." + UUID.randomUUID() + "@tripbus.co";
        JsonNode registro = leer(mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegistroRequest(email, PASSWORD, "CC-" + UUID.randomUUID(), "Ana Pérez"))))
                .andExpect(status().isCreated()));
        token = registro.get("accessToken").asText();
        userId = UUID.fromString(registro.get("userId").asText());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private JsonNode claims(String jwt) throws Exception {
        return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8));
    }

    private String tokenDeVerificacionEnviado() {
        ArgumentCaptor<String> plano = ArgumentCaptor.forClass(String.class);
        verify(emailSender).enviarCorreoVerificacion(eq(email), eq("Ana Pérez"), plano.capture());
        return plano.getValue();
    }

    /** Un token emitido (bien firmado) antes del cambio de contraseña. */
    private String tokenEmitidoHace(Duration hace) {
        Instant iat = Instant.now().minus(hace);
        return Jwts.builder().header().keyId(keyProvider.getKeyId()).and()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("role", "PASAJERO")
                .issuedAt(Date.from(iat))
                .expiration(Date.from(iat.plus(Duration.ofMinutes(60))))
                .signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private ResultActions cambiarContrasena(String jwt, String actual, String nueva) throws Exception {
        return mockMvc.perform(post("/users/me/password")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("passwordActual", actual, "passwordNueva", nueva))));
    }

    // --- US-128: perfil ---

    @Test
    void getMeDevuelveMiPerfilConEmailVerificadoEnFalseYSinPasswordHash() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.nombre").value("Ana Pérez"))
                .andExpect(jsonPath("$.rol").value("PASAJERO"))
                .andExpect(jsonPath("$.emailVerificado").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getMeSinTokenDevuelve401() throws Exception {
        mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void putMeCambiaNombreYTelefonoPeroNoElEmail() throws Exception {
        mockMvc.perform(put("/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Ana María", "telefono", "3001234567",
                                "email", "otro@tripbus.co"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Ana María"))
                .andExpect(jsonPath("$.telefono").value("3001234567"))
                .andExpect(jsonPath("$.email").value(email));

        Usuario guardado = usuarioRepository.findById(userId).orElseThrow();
        assertThat(guardado.getTelefono()).isEqualTo("3001234567");
        assertThat(guardado.getEmail()).isEqualTo(email);
    }

    @Test
    void putMeConTelefonoQueNoEsCelularColombianoDevuelve400() throws Exception {
        for (String telefono : new String[]{"2001234567", "300123456", "30012345678", "300-123-4567"}) {
            mockMvc.perform(put("/users/me")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("nombre", "Ana", "telefono", telefono))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details.telefono").exists());
        }
    }

    @Test
    void putMeSinNombreDevuelve400() throws Exception {
        mockMvc.perform(put("/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.nombre").exists());
    }

    // --- US-129: cambiar contraseña ---

    @Test
    void cambiarContrasenaConLaActualIncorrectaDevuelve400InvalidCurrentPassword() throws Exception {
        cambiarContrasena(token, "Incorrecta1", "NuevaPass1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void cambiarContrasenaAplicaLasReglasDelRegistro() throws Exception {
        cambiarContrasena(token, PASSWORD, "debil")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.passwordNueva").exists());
    }

    @Test
    void cambiarContrasenaDevuelveUnTokenNuevoYLosAnterioresDejanDeValer() throws Exception {
        String tokenViejo = tokenEmitidoHace(Duration.ofSeconds(30));
        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + tokenViejo))
                .andExpect(status().isOk());

        JsonNode respuesta = leer(cambiarContrasena(token, PASSWORD, "NuevaPass1").andExpect(status().isOk()));
        String tokenNuevo = respuesta.get("accessToken").asText();

        // El token emitido antes del cambio ya no vale (ni en el filtro ni en /auth/validate).
        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + tokenViejo))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + tokenViejo))
                .andExpect(status().isUnauthorized());
        // El nuevo mantiene la sesión abierta.
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + tokenNuevo))
                .andExpect(status().isOk());

        verify(emailSender).enviarAvisoCambioContrasena(email, "Ana Pérez");
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "NuevaPass1"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    // --- US-130: verificar correo ---

    @Test
    void alRegistrarseSeEnviaLaVerificacionYAlVerificarQuedaEnElPerfilYEnElToken() throws Exception {
        String tokenVerificacion = tokenDeVerificacionEnviado();
        assertThat(claims(token).get("emailVerificado").asBoolean()).isFalse();

        mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", tokenVerificacion))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.emailVerificado").value(true));
        assertThat(usuarioRepository.findById(userId).orElseThrow().getEmailVerificadoEn()).isNotNull();

        JsonNode login = leer(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", PASSWORD)))));
        assertThat(claims(login.get("accessToken").asText()).get("emailVerificado").asBoolean()).isTrue();
    }

    @Test
    void sinVerificarElCorreoSePuedeIniciarSesion() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void verificarDosVecesConElMismoTokenFallaLaSegunda() throws Exception {
        String tokenVerificacion = tokenDeVerificacionEnviado();
        String body = json(Map.of("token", tokenVerificacion));

        mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID_OR_EXPIRED"));
    }

    @Test
    void verificarConUnTokenVencidoFalla() throws Exception {
        String tokenVerificacion = tokenDeVerificacionEnviado();
        for (EmailVerificationToken t : verificationTokenRepository.findAll()) {
            if (t.getUsuarioId().equals(userId)) {
                t.setExpiresAt(Instant.now().minusSeconds(1));
                verificationTokenRepository.save(t);
            }
        }

        mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", tokenVerificacion))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID_OR_EXPIRED"));
        assertThat(usuarioRepository.findById(userId).orElseThrow().isEmailVerificado()).isFalse();
    }

    @Test
    void verificarSinTokenDevuelve400() throws Exception {
        mockMvc.perform(post("/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void reenviarHastaTresVecesPorHoraYLaCuartaDevuelve429() throws Exception {
        clearInvocations(emailSender);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/verify-email/resend").header("Authorization", "Bearer " + token))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(post("/auth/verify-email/resend").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));

        verify(emailSender, times(3)).enviarCorreoVerificacion(eq(email), eq("Ana Pérez"),
                anyString());
    }

    @Test
    void reenviarConElCorreoYaVerificadoNoEnviaNada() throws Exception {
        Usuario usuario = usuarioRepository.findById(userId).orElseThrow();
        usuario.setEmailVerificado(true);
        usuarioRepository.save(usuario);

        mockMvc.perform(post("/auth/verify-email/resend").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tu correo ya está verificado"));
    }

    @Test
    void reenviarSinSesionDevuelve401() throws Exception {
        mockMvc.perform(post("/auth/verify-email/resend")).andExpect(status().isUnauthorized());
    }
}
