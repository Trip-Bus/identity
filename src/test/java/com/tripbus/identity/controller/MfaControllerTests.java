package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.Base32;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-106 de punta a punta con H2: setup, enable, login en dos pasos, recuperación y disable. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaControllerTests {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private VerificacionDosPasosRepository verificaciones;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private JwtSigner jwtSigner;
    @Autowired
    private JdbcTemplate jdbc;

    private Usuario admin;
    private String tokenAdmin;

    @BeforeEach
    void crearAdmin() {
        admin = guardar(RolUsuario.ADMIN);
        tokenAdmin = token(admin);
    }

    private Usuario guardar(RolUsuario rol) {
        String sufijo = UUID.randomUUID().toString();
        Usuario u = new Usuario("mfa." + sufijo + "@tripbus.co", passwordHasher.hash(PASSWORD), "CC-" + sufijo,
                "Persona MFA");
        u.setRol(rol);
        return usuarioRepository.saveAndFlush(u);
    }

    private String token(Usuario u) {
        return "Bearer " + jwtSigner.sign(u.getId(), u.getRol(), u.getCompanyId(), u.getEmail(), false,
                Duration.ofMinutes(5));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private ResultActions postJson(String ruta, String authorization, Object body) throws Exception {
        var request = post(ruta).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mockMvc.perform(request);
    }

    private byte[] secreto() {
        return cifrador.descifrar(verificaciones.findById(admin.getId()).orElseThrow().getSecretoCifrado(),
                admin.getId().toString());
    }

    /** setup + enable con el código del paso actual. Devuelve los códigos de recuperación. */
    private List<String> activar(long paso) throws Exception {
        JsonNode setup = leer(postJson("/users/me/2fa/setup", tokenAdmin, Map.of()).andExpect(status().isOk()));
        assertThat(setup.get("secret").asText()).isEqualTo(Base32.codificar(secreto()));
        assertThat(setup.get("otpauthUri").asText())
                .startsWith("otpauth://totp/TripBus:").contains("secret=" + setup.get("secret").asText());

        JsonNode enable = leer(postJson("/users/me/2fa/enable", tokenAdmin,
                Map.of("code", Totp.codigo(secreto(), paso))).andExpect(status().isOk()));
        List<String> codigos = new ArrayList<>();
        enable.get("recoveryCodes").forEach(c -> codigos.add(c.asText()));
        return codigos;
    }

    private String loginConSegundoPaso() throws Exception {
        JsonNode login = leer(postJson("/auth/login", null, Map.of("email", admin.getEmail(), "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist()));
        return login.get("mfaToken").asText();
    }

    private ResultActions segundoPaso(String mfaToken, String code, String recoveryCode) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("mfaToken", mfaToken);
        body.put("code", code);
        body.put("recoveryCode", recoveryCode);
        return postJson("/auth/2fa", null, body);
    }

    @Test
    void flujoCompletoSetupEnableYLoginEnDosPasos() throws Exception {
        long paso = Totp.paso(Instant.now());
        List<String> codigos = activar(paso);
        assertThat(codigos).hasSize(10);

        String mfaToken = loginConSegundoPaso();
        JsonNode tokens = leer(segundoPaso(mfaToken, Totp.codigo(secreto(), paso + 1), null)
                .andExpect(status().isOk()));

        assertThat(tokens.get("refreshToken").asText()).isNotBlank();
        mockMvc.perform(get("/users/me/data").header("Authorization", "Bearer " + tokens.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificacionDosPasos").value(true));
    }

    @Test
    void unCodigoYaUsadoSeRechazaYElMismoMfaTokenNoSirveDosVeces() throws Exception {
        long paso = Totp.paso(Instant.now());
        activar(paso);

        String mfaToken = loginConSegundoPaso();
        segundoPaso(mfaToken, Totp.codigo(secreto(), paso), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        segundoPaso(mfaToken, Totp.codigo(secreto(), paso + 1), null).andExpect(status().isOk());
        segundoPaso(mfaToken, Totp.codigo(secreto(), paso + 1), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MFA_TOKEN_INVALID"));

        segundoPaso(loginConSegundoPaso(), Totp.codigo(secreto(), paso + 1), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
    }

    @Test
    void elCodigoDeRecuperacionSirveUnaSolaVez() throws Exception {
        List<String> codigos = activar(Totp.paso(Instant.now()));

        segundoPaso(loginConSegundoPaso(), null, codigos.get(0).toLowerCase()).andExpect(status().isOk());
        segundoPaso(loginConSegundoPaso(), null, codigos.get(0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        segundoPaso(loginConSegundoPaso(), null, codigos.get(1)).andExpect(status().isOk());
    }

    @Test
    void unMfaTokenVencidoSeRechaza() throws Exception {
        long paso = Totp.paso(Instant.now());
        activar(paso);
        String mfaToken = loginConSegundoPaso();
        jdbc.update("update mfa_desafios set expira_en = ? where usuario_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(1))), admin.getId());

        segundoPaso(mfaToken, Totp.codigo(secreto(), paso + 1), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MFA_TOKEN_INVALID"));
    }

    @Test
    void cincoFallosConElMismoMfaTokenLoInvalidan() throws Exception {
        long paso = Totp.paso(Instant.now());
        activar(paso);
        String mfaToken = loginConSegundoPaso();
        String correcto = Totp.codigo(secreto(), paso + 1);
        String incorrecto = correcto.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            segundoPaso(mfaToken, incorrecto, null).andExpect(status().isUnauthorized());
        }
        segundoPaso(mfaToken, correcto, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("MFA_TOKEN_INVALID"));
    }

    @Test
    void sinCodigoNiRecuperacionEsUnErrorDeValidacion() throws Exception {
        segundoPaso("cualquiera", null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unPasajeroNoPuedeActivarLaDosPasos() throws Exception {
        postJson("/users/me/2fa/setup", token(guardar(RolUsuario.PASAJERO)), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void enableSinSetupYSetupConLaDosPasosActivaDan409() throws Exception {
        postJson("/users/me/2fa/enable", tokenAdmin, Map.of("code", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MFA_NOT_SET_UP"));

        activar(Totp.paso(Instant.now()));

        postJson("/users/me/2fa/setup", tokenAdmin, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MFA_ALREADY_ENABLED"));
    }

    @Test
    void desactivarExigeContrasenaYCodigoYDespuesElLoginEntregaTokens() throws Exception {
        long paso = Totp.paso(Instant.now());
        activar(paso);

        postJson("/users/me/2fa/disable", tokenAdmin, Map.of("password", "Equivocada1",
                "code", Totp.codigo(secreto(), paso + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));
        postJson("/users/me/2fa/disable", tokenAdmin, Map.of("password", PASSWORD,
                "code", Totp.codigo(secreto(), paso + 1)))
                .andExpect(status().isNoContent());

        assertThat(verificaciones.findById(admin.getId())).isEmpty();
        postJson("/auth/login", null, Map.of("email", admin.getEmail(), "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        postJson("/users/me/2fa/disable", tokenAdmin, Map.of("password", PASSWORD, "code", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MFA_NOT_ENABLED"));
    }
}
