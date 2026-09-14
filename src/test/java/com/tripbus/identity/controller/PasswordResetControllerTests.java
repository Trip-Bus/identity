package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.PasswordResetConfirmRequest;
import com.tripbus.identity.dto.PasswordResetRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.PasswordResetToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.PasswordResetTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordResetControllerTests.TestConfig.class)
class PasswordResetControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private RecordingEmailSender recordingEmailSender;

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {

        private String ultimoTokenPlano;

        @Override
        public void enviarCorreoRecuperacion(String email, String tokenPlano) {
            this.ultimoTokenPlano = tokenPlano;
        }

        String getUltimoTokenPlano() {
            return ultimoTokenPlano;
        }
    }

    @BeforeEach
    void limpiarBaseDeDatos() {
        passwordResetTokenRepository.deleteAll();
        usuarioRepository.deleteAll();
    }

    private void registrar(String email, String documento) throws Exception {
        RegistroRequest registro = new RegistroRequest(email, "Passw0rd1", documento, "Usuario De Prueba");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());
    }

    @Test
    void solicitarRecuperacionConEmailExistenteDevuelve202YCreaUnTokenConExpiracionDeQuinceMinutos()
            throws Exception {
        registrar("existe@tripbus.com", "CC-7001");
        Instant antes = Instant.now();

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("existe@tripbus.com"))))
                .andExpect(status().isAccepted());

        assertThat(passwordResetTokenRepository.findAll()).hasSize(1);
        PasswordResetToken token = passwordResetTokenRepository.findAll().get(0);
        assertThat(token.getUsedAt()).isNull();
        assertThat(token.getExpiresAt()).isBetween(
                antes.plus(Duration.ofMinutes(15)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(5));
    }

    @Test
    void solicitarRecuperacionConEmailInexistenteDevuelve202PeroNoCreaToken() throws Exception {
        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("noexiste@tripbus.com"))))
                .andExpect(status().isAccepted());

        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
    }

    @Test
    void confirmarConTokenValidoDevuelve200YCambiaLaContrasena() throws Exception {
        registrar("cambio@tripbus.com", "CC-7002");

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("cambio@tripbus.com"))))
                .andExpect(status().isAccepted());

        String tokenPlano = recordingEmailSender.getUltimoTokenPlano();

        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest(tokenPlano, "NuevaPass1"))))
                .andExpect(status().isOk());

        Usuario actualizado = usuarioRepository.findByEmail("cambio@tripbus.com").orElseThrow();
        assertThat(passwordHasher.matches("NuevaPass1", actualizado.getPasswordHash())).isTrue();
        assertThat(actualizado.getPasswordChangedAt()).isNotNull();

        PasswordResetToken token = passwordResetTokenRepository.findAll().get(0);
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void confirmarElMismoTokenUnaSegundaVezFalla() throws Exception {
        registrar("reuso@tripbus.com", "CC-7003");

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("reuso@tripbus.com"))))
                .andExpect(status().isAccepted());

        String tokenPlano = recordingEmailSender.getUltimoTokenPlano();
        PasswordResetConfirmRequest confirmacion = new PasswordResetConfirmRequest(tokenPlano, "NuevaPass1");

        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmacion)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmacion)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID_OR_EXPIRED"));
    }

    @Test
    void confirmarConTokenExpiradoFalla() throws Exception {
        registrar("expirado@tripbus.com", "CC-7004");

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("expirado@tripbus.com"))))
                .andExpect(status().isAccepted());

        String tokenPlano = recordingEmailSender.getUltimoTokenPlano();

        PasswordResetToken token = passwordResetTokenRepository.findAll().get(0);
        token.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        passwordResetTokenRepository.save(token);

        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest(tokenPlano, "NuevaPass1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID_OR_EXPIRED"));
    }

    @Test
    void restablecerContrasenaInvalidaLosJwtEmitidosAntesDelCambio() throws Exception {
        registrar("sesion@tripbus.com", "CC-7005");

        String cuerpoLogin = objectMapper.writeValueAsString(new LoginRequest("sesion@tripbus.com", "Passw0rd1"));
        String respuestaLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoLogin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthResponse loginResponse = objectMapper.readValue(respuestaLogin, AuthResponse.class);
        String tokenAntiguo = loginResponse.accessToken();

        // El JWT antiguo funciona antes del cambio de contraseña.
        mockMvc.perform(get("/users/" + loginResponse.userId())
                        .header("Authorization", "Bearer " + tokenAntiguo))
                .andExpect(status().isOk());

        // El claim "iat" del JWT tiene precisión de segundos (NumericDate, RFC 7519).
        // Esta pausa garantiza que el login anterior quede en un segundo distinto al
        // del cambio de contraseña que sigue, para que la invalidación sea
        // determinística y no dependa de la velocidad de ejecución de la máquina.
        Thread.sleep(1100);

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("sesion@tripbus.com"))))
                .andExpect(status().isAccepted());

        String tokenPlano = recordingEmailSender.getUltimoTokenPlano();

        mockMvc.perform(post("/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest(tokenPlano, "OtraPass2"))))
                .andExpect(status().isOk());

        // El JWT emitido antes del cambio ahora debe ser rechazado.
        mockMvc.perform(get("/users/" + loginResponse.userId())
                        .header("Authorization", "Bearer " + tokenAntiguo))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        // Un login nuevo con la nueva contraseña sigue funcionando con normalidad.
        String cuerpoLoginNuevo =
                objectMapper.writeValueAsString(new LoginRequest("sesion@tripbus.com", "OtraPass2"));
        String respuestaLoginNuevo = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoLoginNuevo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthResponse loginNuevo = objectMapper.readValue(respuestaLoginNuevo, AuthResponse.class);

        mockMvc.perform(get("/users/" + loginNuevo.userId())
                        .header("Authorization", "Bearer " + loginNuevo.accessToken()))
                .andExpect(status().isOk());
    }
}
