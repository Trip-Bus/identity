package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.AuditoriaRepository;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-131 (Ley 1581) de punta a punta con H2: descargar y eliminar mis datos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatosPersonalesControllerTests {

    private static final String PASSWORD = "Passw0rd1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ConsentimientoRepository consentimientoRepository;
    @Autowired
    private AuditoriaRepository auditoriaRepository;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private JwtSigner jwtSigner;

    private String email;
    private String documento;
    private String accessToken;
    private String refreshToken;
    private UUID userId;

    @BeforeEach
    void registrar() throws Exception {
        String sufijo = UUID.randomUUID().toString();
        email = "datos." + sufijo + "@tripbus.co";
        documento = "CC-" + sufijo;
        JsonNode registro = leer(mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistroRequest(email, PASSWORD, documento, "Ana Pérez"))))
                .andExpect(status().isCreated()));
        accessToken = "Bearer " + registro.get("accessToken").asText();
        refreshToken = registro.get("refreshToken").asText();
        userId = UUID.fromString(registro.get("userId").asText());
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private ResultActions eliminar(String authorization, String password, String confirmacion) throws Exception {
        return mockMvc.perform(post("/users/me/delete")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password, "confirmacion", confirmacion))));
    }

    @Test
    void laExportacionTraeTodosMisDatosYNingunSecreto() throws Exception {
        String cuerpo = mockMvc.perform(get("/users/me/data").header("Authorization", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil.id").value(userId.toString()))
                .andExpect(jsonPath("$.perfil.email").value(email))
                .andExpect(jsonPath("$.perfil.nombre").value("Ana Pérez"))
                .andExpect(jsonPath("$.perfil.documentoIdentidad").value(documento))
                .andExpect(jsonPath("$.rol").value("PASAJERO"))
                .andExpect(jsonPath("$.emailVerificado").value(false))
                .andExpect(jsonPath("$.consentimientos[0].politica").value("POLITICA_TRATAMIENTO_DATOS"))
                .andExpect(jsonPath("$.consentimientos[0].aceptadoEn").isNotEmpty())
                .andExpect(jsonPath("$.sesionesActivas.length()").value(1))
                .andExpect(jsonPath("$.sesionesActivas[0].familiaId").isNotEmpty())
                .andExpect(jsonPath("$.verificacionDosPasos").value(false))
                .andReturn().getResponse().getContentAsString();

        String hashGuardado = usuarioRepository.findById(userId).orElseThrow().getPasswordHash();
        assertThat(cuerpo).doesNotContain(hashGuardado).doesNotContain(refreshToken)
                .doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("secret")
                .doesNotContain(PASSWORD);
    }

    @Test
    void conContrasenaIncorrectaNoSeEliminaNada() throws Exception {
        eliminar(accessToken, "Equivocada1", "ELIMINAR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));

        Usuario u = usuarioRepository.findById(userId).orElseThrow();
        assertThat(u.isActivo()).isTrue();
        assertThat(u.getEmail()).isEqualTo(email);
    }

    @Test
    void sinEscribirEliminarEsUnErrorDeValidacion() throws Exception {
        eliminar(accessToken, PASSWORD, "eliminar")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.confirmacion").exists());
    }

    @Test
    void eliminarAnonimizaRevocaSesionesYConsentimientoYAudita() throws Exception {
        eliminar(accessToken, PASSWORD, "ELIMINAR").andExpect(status().isNoContent());

        Usuario u = usuarioRepository.findById(userId).orElseThrow();
        assertThat(u.getEmail()).startsWith("deleted+").endsWith("@tripbus.invalid");
        assertThat(u.getNombre()).isEqualTo("Usuario eliminado");
        assertThat(u.getDocumentoIdentidad()).isNull();
        assertThat(u.getTelefono()).isNull();
        assertThat(u.isActivo()).isFalse();
        assertThat(consentimientoRepository.findByUsuarioId(userId))
                .isNotEmpty().allSatisfy(c -> assertThat(c.getRevocadoEn()).isNotNull());
        assertThat(auditoriaRepository.findAll())
                .anySatisfy(r -> {
                    assertThat(r.getAccion()).isEqualTo(AccionAuditoria.ELIMINAR_CUENTA);
                    assertThat(r.getActorId()).isEqualTo(userId);
                    assertThat(r.getTargetUserId()).isEqualTo(userId);
                    assertThat(r.getDespues()).containsEntry("anonimizada", true);
                });

        // El token viejo, el refresh token y el login con el correo anterior ya no sirven.
        mockMvc.perform(get("/users/me").header("Authorization", accessToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        // El documento y el correo quedan libres: la persona puede volver a registrarse.
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegistroRequest(email, PASSWORD, documento, "Ana Pérez"))))
                .andExpect(status().isCreated());
    }

    @Test
    void elUnicoAdminActivoNoPuedeEliminarseYConOtroAdminSi() throws Exception {
        usuarioRepository.findAll().stream()
                .filter(u -> u.getRol() == RolUsuario.ADMIN && u.isActivo())
                .forEach(u -> {
                    u.setActivo(false);
                    usuarioRepository.save(u);
                });
        Usuario admin = guardarAdmin();
        String tokenAdmin = "Bearer " + jwtSigner.sign(admin.getId(), RolUsuario.ADMIN, null, admin.getEmail(), false,
                Duration.ofMinutes(5));

        eliminar(tokenAdmin, PASSWORD, "ELIMINAR")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_ADMIN"));

        guardarAdmin();
        eliminar(tokenAdmin, PASSWORD, "ELIMINAR").andExpect(status().isNoContent());
    }

    private Usuario guardarAdmin() {
        String sufijo = UUID.randomUUID().toString();
        Usuario admin = new Usuario("admin." + sufijo + "@tripbus.co", passwordHasher.hash(PASSWORD), "CC-" + sufijo,
                "Admin");
        admin.setRol(RolUsuario.ADMIN);
        return usuarioRepository.saveAndFlush(admin);
    }
}
