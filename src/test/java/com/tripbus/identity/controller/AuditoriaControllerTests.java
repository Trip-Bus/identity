package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.AuditoriaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-105 con H2: cada acción administrativa queda en admin_audit_log y se consulta con GET /admin/audit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditoriaControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private AuditoriaRepository auditoriaRepository;
    @Autowired
    private JwtSigner jwtSigner;

    @MockitoBean
    private CatalogClient catalogClient;

    private Usuario admin;
    private String tokenAdmin;
    private Usuario objetivo;
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        admin = guardar(RolUsuario.ADMIN);
        tokenAdmin = token(admin);
        objetivo = guardar(RolUsuario.PASAJERO);
    }

    private Usuario guardar(RolUsuario rol) {
        String sufijo = UUID.randomUUID().toString();
        Usuario u = new Usuario("audit." + sufijo + "@tripbus.co", "hash", "CC-" + sufijo, "Persona " + rol);
        u.setRol(rol);
        return usuarioRepository.saveAndFlush(u);
    }

    private String token(Usuario u) {
        return "Bearer " + jwtSigner.sign(u.getId(), u.getRol(), u.getCompanyId(), u.getEmail(), false,
                Duration.ofMinutes(5));
    }

    /** Fechas distintas entre acciones: el orden del resultado no depende de empates. */
    private static void pausa() throws InterruptedException {
        Thread.sleep(5);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode auditoria(String filtros) throws Exception {
        ResultActions r = mockMvc.perform(get("/admin/audit?" + filtros).header("Authorization", tokenAdmin))
                .andExpect(status().isOk());
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void cadaAccionAdministrativaQuedaAuditadaConAntesDespuesYMotivo() throws Exception {
        Instant inicio = Instant.now().minusSeconds(1);
        UUID id = objetivo.getId();

        mockMvc.perform(put("/users/" + id + "/role").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rol", "OPERADOR", "companyId", companyId))))
                .andExpect(status().isOk());
        pausa();
        mockMvc.perform(patch("/users/" + id + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("activo", false, "motivo", "Documentos vencidos"))))
                .andExpect(status().isOk());
        pausa();
        mockMvc.perform(patch("/users/" + id + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("activo", true, "motivo", "Aclarado"))))
                .andExpect(status().isOk());
        pausa();
        mockMvc.perform(delete("/users/" + id + "/lock").header("Authorization", tokenAdmin))
                .andExpect(status().isNoContent());

        JsonNode pagina = auditoria("targetUserId=" + id + "&desde=" + inicio);

        assertThat(pagina.get("totalElements").asLong()).isEqualTo(4);
        JsonNode registros = pagina.get("content");
        // Del más reciente al más antiguo.
        assertThat(registros.get(0).get("accion").asText()).isEqualTo("DESBLOQUEAR");
        assertThat(registros.get(1).get("accion").asText()).isEqualTo("REACTIVAR");
        assertThat(registros.get(1).get("motivo").asText()).isEqualTo("Aclarado");
        assertThat(registros.get(2).get("accion").asText()).isEqualTo("SUSPENDER");
        assertThat(registros.get(2).at("/antes/activo").asBoolean()).isTrue();
        assertThat(registros.get(2).at("/despues/activo").asBoolean()).isFalse();
        assertThat(registros.get(3).get("accion").asText()).isEqualTo("CAMBIAR_ROL");
        assertThat(registros.get(3).at("/antes/rol").asText()).isEqualTo("PASAJERO");
        assertThat(registros.get(3).at("/despues/rol").asText()).isEqualTo("OPERADOR");
        assertThat(registros.get(3).at("/despues/companyId").asText()).isEqualTo(companyId.toString());
        assertThat(registros).allSatisfy(r -> assertThat(r.get("actorId").asText()).isEqualTo(admin.getId().toString()));
        assertThat(pagina.toString()).doesNotContain(objetivo.getEmail());
    }

    @Test
    void crearUnOperadorQuedaAuditadoYSeFiltraPorAccionYActor() throws Exception {
        String correo = "nuevo." + UUID.randomUUID() + "@tripbus.co";
        JsonNode creado = objectMapper.readTree(mockMvc.perform(post("/users").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Luis", "email", correo, "documentoIdentidad",
                                "CC-" + UUID.randomUUID(), "rol", "CONDUCTOR", "companyId", companyId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        JsonNode pagina = auditoria("accion=CREAR_USUARIO&actorId=" + admin.getId());

        assertThat(pagina.get("totalElements").asLong()).isEqualTo(1);
        JsonNode registro = pagina.at("/content/0");
        assertThat(registro.get("targetUserId").asText()).isEqualTo(creado.get("id").asText());
        assertThat(registro.get("antes").isNull()).isTrue();
        assertThat(registro.at("/despues/rol").asText()).isEqualTo("CONDUCTOR");
    }

    @Test
    void unaAccionQueFallaNoQuedaAuditada() throws Exception {
        doThrow(new EmpresaInactivaException()).when(catalogClient).validarEmpresaActiva(companyId);

        mockMvc.perform(put("/users/" + objetivo.getId() + "/role").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rol", "OPERADOR", "companyId", companyId))))
                .andExpect(status().isBadRequest());

        assertThat(auditoriaRepository.findAll()).noneSatisfy(r ->
                assertThat(r.getTargetUserId()).isEqualTo(objetivo.getId()));
    }

    @Test
    void elRangoDeFechasYLaPaginacionFiltran() throws Exception {
        mockMvc.perform(delete("/users/" + objetivo.getId() + "/lock").header("Authorization", tokenAdmin))
                .andExpect(status().isNoContent());

        assertThat(auditoria("targetUserId=" + objetivo.getId() + "&hasta=" + Instant.now().minusSeconds(3600))
                .get("totalElements").asLong()).isZero();
        JsonNode pagina = auditoria("targetUserId=" + objetivo.getId() + "&page=0&size=500");
        assertThat(pagina.get("size").asInt()).isEqualTo(100);
        assertThat(pagina.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void soloUnAdminConsultaLaAuditoriaYLosFiltrosInvalidosDan400() throws Exception {
        mockMvc.perform(get("/admin/audit").header("Authorization", token(objetivo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/admin/audit?accion=BORRAR_TODO").header("Authorization", tokenAdmin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/admin/audit?desde=ayer").header("Authorization", tokenAdmin))
                .andExpect(status().isBadRequest());
    }
}
