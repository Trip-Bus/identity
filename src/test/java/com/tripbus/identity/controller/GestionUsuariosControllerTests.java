package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.exception.CatalogNoDisponibleException;
import com.tripbus.identity.exception.EmpresaInactivaException;
import com.tripbus.identity.exception.EmpresaNoEncontradaException;
import com.tripbus.identity.model.Consentimiento;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.PasswordResetTokenRepository;
import com.tripbus.identity.repository.SesionRevocadaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-101 a US-104 con H2: catalog, Redis y el envío de correo se simulan. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GestionUsuariosControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ConsentimientoRepository consentimientoRepository;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired
    private SesionRevocadaRepository sesionRevocadaRepository;
    @Autowired
    private JwtSigner jwtSigner;

    @MockitoBean
    private CatalogClient catalogClient;
    @MockitoBean
    private SuspensionPublisher suspensionPublisher;
    @MockitoBean
    private IdentityEventPublisher events;
    @MockitoBean
    private EmailSender emailSender;

    private Usuario admin;
    private String tokenAdmin;
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consentimientoRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        sesionRevocadaRepository.deleteAll();
        usuarioRepository.deleteAll();
        admin = guardar("Admin Central", "admin@tripbus.co", "CC-ADMIN", RolUsuario.ADMIN, null, true);
        tokenAdmin = token(admin);
    }

    private Usuario guardar(String nombre, String email, String documento, RolUsuario rol, UUID empresa,
                            boolean activo) {
        try {
            Thread.sleep(3); // fechas de creación distintas: el orden del listado no depende de empates
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Usuario u = new Usuario(email, "hash", documento, nombre);
        u.setRol(rol);
        u.setCompanyId(empresa);
        u.setActivo(activo);
        return usuarioRepository.saveAndFlush(u);
    }

    private String token(Usuario u) {
        return "Bearer " + jwtSigner.sign(u.getId(), u.getRol(), u.getCompanyId(), u.getEmail(), Duration.ofMinutes(5));
    }

    private String crearBody(String email, String documento, String rol, UUID empresa) {
        return """
                {"nombre":"Carlos Ruíz","email":"%s","documentoIdentidad":"%s","rol":"%s","companyId":%s}
                """.formatted(email, documento, rol, empresa == null ? "null" : "\"" + empresa + "\"");
    }

    // --- US-102 ---

    @Test
    void listarDevuelvePaginaOrdenadaDelMasRecienteSinPasswordHash() throws Exception {
        guardar("Viejo", "viejo@tripbus.co", "CC-1", RolUsuario.PASAJERO, null, true);
        guardar("Nuevo", "nuevo@tripbus.co", "CC-2", RolUsuario.PASAJERO, null, true);

        String body = mockMvc.perform(get("/users").param("size", "2").header("Authorization", tokenAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].email").value("nuevo@tripbus.co"))
                .andExpect(jsonPath("$.content[1].email").value("viejo@tripbus.co"))
                .andExpect(jsonPath("$.content[0].fechaCreacion").exists())
                .andExpect(jsonPath("$.content[0].activo").value(true))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("passwordHash").doesNotContain("\"hash\"");
    }

    @Test
    void listarFiltraPorRolEmpresaYActivo() throws Exception {
        guardar("Op Uno", "op1@tripbus.co", "CC-3", RolUsuario.OPERADOR, companyId, true);
        guardar("Op Dos", "op2@tripbus.co", "CC-4", RolUsuario.OPERADOR, companyId, false);
        guardar("Op Otra", "op3@tripbus.co", "CC-5", RolUsuario.OPERADOR, UUID.randomUUID(), true);
        guardar("Cond", "cond@tripbus.co", "CC-6", RolUsuario.CONDUCTOR, companyId, true);

        mockMvc.perform(get("/users").header("Authorization", tokenAdmin)
                        .param("rol", "OPERADOR").param("companyId", companyId.toString()).param("activo", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("op1@tripbus.co"));
    }

    @Test
    void qBuscaEnNombreEmailYDocumentoSinDistinguirMayusculasNiTildes() throws Exception {
        guardar("José Pérez", "jose@tripbus.co", "CC-100", RolUsuario.PASAJERO, null, true);
        guardar("Ana Gómez", "ana@correo.com", "TI-555", RolUsuario.PASAJERO, null, true);

        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("q", "JOSE perez"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].nombre").value("José Pérez"));
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("q", "gomez"))
                .andExpect(jsonPath("$.content[0].email").value("ana@correo.com"));
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("q", "CORREO.COM"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("q", "ti-555"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("q", "%"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void sizeMayorA100SeLimitaA100() throws Exception {
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rolInvalidoEnElFiltroDa400() throws Exception {
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin).param("rol", "CHOFER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.rol").exists());
    }

    @ParameterizedTest
    @EnumSource(value = RolUsuario.class, names = {"PASAJERO", "CONDUCTOR", "OPERADOR"})
    void soloUnAdminPuedeListarCrearOSuspender(RolUsuario rol) throws Exception {
        Usuario otro = guardar("Otro", "otro@tripbus.co", "CC-OTRO", rol, rol == RolUsuario.PASAJERO ? null : companyId,
                true);
        String tokenOtro = token(otro);

        mockMvc.perform(get("/users").header("Authorization", tokenOtro))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/users").header("Authorization", tokenOtro).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("x@tripbus.co", "CC-X", "OPERADOR", companyId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/users/" + admin.getId() + "/estado").header("Authorization", tokenOtro)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenListarDa401() throws Exception {
        mockMvc.perform(get("/users")).andExpect(status().isUnauthorized());
    }

    // --- US-103 ---

    @Test
    void crearOperadorDa201ConConsentimientoDelAdminTokenDeRecuperacionYEvento() throws Exception {
        String respuesta = mockMvc.perform(post("/users").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody(" Carlos@TripBus.CO ", "CC-200", "OPERADOR", companyId)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.email").value("carlos@tripbus.co"))
                .andExpect(jsonPath("$.rol").value("OPERADOR"))
                .andExpect(jsonPath("$.companyId").value(companyId.toString()))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(respuesta).get("id").asText());

        verify(catalogClient).validarEmpresaActiva(companyId);
        List<Consentimiento> consentimientos = consentimientoRepository.findByUsuarioId(id);
        assertThat(consentimientos).singleElement().satisfies(c -> {
            assertThat(c.getOrigen()).isEqualTo(Consentimiento.ORIGEN_ADMIN);
            assertThat(c.getOtorgadoPor()).isEqualTo(admin.getId());
        });
        assertThat(passwordResetTokenRepository.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getUsuarioId()).isEqualTo(id));
        verify(emailSender).enviarCorreoRecuperacion(eq("carlos@tripbus.co"), anyString());
        verify(events).publish(eq(IdentityEventPublisher.USER_REGISTERED), any());
    }

    @Test
    void laPersonaCreadaDefineSuContrasenaConElEnlaceYPuedeIniciarSesion() throws Exception {
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("conductor.nuevo@tripbus.co", "CC-201", "CONDUCTOR", companyId)))
                .andExpect(status().isCreated());
        ArgumentCaptor<String> tokenPlano = ArgumentCaptor.forClass(String.class);
        verify(emailSender).enviarCorreoRecuperacion(eq("conductor.nuevo@tripbus.co"), tokenPlano.capture());

        mockMvc.perform(post("/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"nuevaPassword\":\"Conductor1\"}".formatted(tokenPlano.getValue())))
                .andExpect(status().is2xxSuccessful());

        String login = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"conductor.nuevo@tripbus.co\",\"password\":\"Conductor1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("CONDUCTOR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(login).get("accessToken").asText()).isNotBlank();
    }

    @Test
    void crearConEmailODocumentoRepetidoDa409() throws Exception {
        guardar("Existente", "existe@tripbus.co", "CC-300", RolUsuario.PASAJERO, null, true);

        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("EXISTE@tripbus.co", "CC-301", "OPERADOR", companyId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("nuevo2@tripbus.co", "CC-300", "OPERADOR", companyId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENTO_ALREADY_REGISTERED"));
    }

    @Test
    void crearConRolNoPermitidoODatosInvalidosDa400() throws Exception {
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("p@tripbus.co", "CC-400", "PASAJERO", companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.rol").exists());
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("no-es-email", "CC-401", "OPERADOR", companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.email").exists());
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("c@tripbus.co", "CC-402", "CONDUCTOR", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_ID_REQUIRED"));
    }

    @Test
    void crearConEmpresaInexistenteInactivaOCatalogCaidoNoCreaElUsuario() throws Exception {
        doThrow(new EmpresaNoEncontradaException()).when(catalogClient).validarEmpresaActiva(companyId);
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("e1@tripbus.co", "CC-500", "OPERADOR", companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));

        doThrow(new EmpresaInactivaException()).when(catalogClient).validarEmpresaActiva(companyId);
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("e1@tripbus.co", "CC-500", "OPERADOR", companyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));

        doThrow(new CatalogNoDisponibleException()).when(catalogClient).validarEmpresaActiva(companyId);
        mockMvc.perform(post("/users").header("Authorization", tokenAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(crearBody("e1@tripbus.co", "CC-500", "OPERADOR", companyId)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CATALOG_UNAVAILABLE"));

        assertThat(usuarioRepository.existsByEmail("e1@tripbus.co")).isFalse();
        verify(emailSender, never()).enviarCorreoRecuperacion(any(), any());
    }

    // --- US-101 ---

    @Test
    void cambiarRolAOperadorConEmpresaInactivaOCatalogCaidoNoAsignaElRol() throws Exception {
        Usuario pasajero = guardar("Pasajero", "pas@tripbus.co", "CC-600", RolUsuario.PASAJERO, null, true);
        String body = "{\"rol\":\"OPERADOR\",\"companyId\":\"%s\"}".formatted(companyId);

        doThrow(new EmpresaInactivaException()).when(catalogClient).validarEmpresaActiva(companyId);
        mockMvc.perform(put("/users/" + pasajero.getId() + "/role").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_INACTIVE"));

        doThrow(new CatalogNoDisponibleException()).when(catalogClient).validarEmpresaActiva(companyId);
        mockMvc.perform(put("/users/" + pasajero.getId() + "/role").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CATALOG_UNAVAILABLE"));

        assertThat(usuarioRepository.findById(pasajero.getId()).orElseThrow().getRol()).isEqualTo(RolUsuario.PASAJERO);
    }

    // --- US-104 ---

    @Test
    void suspenderInvalidaSusTokensDeInmediatoYReactivarLosVuelveAAceptar() throws Exception {
        Usuario operador = guardar("Operador", "op@tripbus.co", "CC-700", RolUsuario.OPERADOR, companyId, true);
        String tokenOperador = token(operador);
        mockMvc.perform(get("/auth/validate").header("Authorization", tokenOperador)).andExpect(status().isOk());

        mockMvc.perform(patch("/users/" + operador.getId() + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false,\"motivo\":\"Fraude\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        verify(suspensionPublisher).publicarSuspension(operador.getId(), "Fraude", true);

        mockMvc.perform(get("/auth/validate").header("Authorization", tokenOperador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));

        mockMvc.perform(patch("/users/" + operador.getId() + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true));
        verify(suspensionPublisher).publicarReactivacion(operador.getId(), null, true);
        mockMvc.perform(get("/auth/validate").header("Authorization", tokenOperador)).andExpect(status().isOk());
    }

    @Test
    void unAdminNoPuedeSuspenderseASiMismo() throws Exception {
        mockMvc.perform(patch("/users/" + admin.getId() + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false,\"motivo\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CANNOT_SUSPEND_SELF"));
        assertThat(usuarioRepository.findById(admin.getId()).orElseThrow().isActivo()).isTrue();
    }

    @Test
    void cambiarEstadoSinActivoDa400YDeUsuarioInexistenteDa404() throws Exception {
        mockMvc.perform(patch("/users/" + UUID.randomUUID() + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.activo").exists());
        mockMvc.perform(patch("/users/" + UUID.randomUUID() + "/estado").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void fechaCreacionApareceEnElDetalleDelUsuario() throws Exception {
        String body = mockMvc.perform(get("/users/" + admin.getId()).header("Authorization", tokenAdmin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertThat(Instant.parse(json.get("fechaCreacion").asText())).isBeforeOrEqualTo(Instant.now());
    }
}
