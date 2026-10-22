package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAccessControlTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtSigner jwtSigner;

    // US-101: asignar OPERADOR/CONDUCTOR consulta la empresa en catalog; aquí siempre es válida.
    @MockitoBean
    private CatalogClient catalogClient;

    private UUID usuarioObjetivoId;

    @BeforeEach
    void prepararUsuarioObjetivo() throws Exception {
        usuarioRepository.deleteAll();

        RegistroRequest registro =
                new RegistroRequest("objetivo@tripbus.com", "Passw0rd1", "CC-9001", "Usuario Objetivo");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        usuarioObjetivoId = usuarioRepository.findByEmail("objetivo@tripbus.com").orElseThrow().getId();
    }

    // JwtAuthenticationFilter ahora busca al usuario por el "sub" del token (para
    // verificar passwordChangedAt), así que el id firmado debe corresponder a un
    // usuario real en BD; un UUID aleatorio sin usuario asociado se rechaza con 401.
    private String tokenPara(RolUsuario rol) {
        Usuario usuario = new Usuario(
                "token-" + rol.name().toLowerCase() + "-" + UUID.randomUUID() + "@tripbus.com",
                "hash-irrelevante-para-esta-prueba",
                "DOC-" + UUID.randomUUID(),
                "Usuario " + rol.name());
        usuario.setRol(rol);
        usuario = usuarioRepository.save(usuario);
        return jwtSigner.sign(usuario.getId(), rol, null, usuario.getEmail(), Duration.ofMinutes(5));
    }

    @Test
    void requestSinTokenAUnEndpointProtegidoDevuelve401ConUnauthenticated() throws Exception {
        mockMvc.perform(get("/users/" + usuarioObjetivoId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void requestConTokenExpiradoDevuelve401ConUnauthenticated() throws Exception {
        String tokenExpirado = jwtSigner.sign(UUID.randomUUID(), RolUsuario.ADMIN, null, null, Duration.ofMinutes(-5));

        mockMvc.perform(get("/users/" + usuarioObjetivoId)
                        .header("Authorization", "Bearer " + tokenExpirado))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void cambiarRolConTokenPasajeroDevuelve403ConForbidden() throws Exception {
        CambiarRolRequest cambio = new CambiarRolRequest(RolUsuario.OPERADOR, UUID.randomUUID());

        mockMvc.perform(put("/users/" + usuarioObjetivoId + "/role")
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.PASAJERO))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cambio)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void cambiarRolConTokenAdminDevuelve200YCambiaElRolEnBaseDeDatos() throws Exception {
        UUID companyId = UUID.randomUUID();
        CambiarRolRequest cambio = new CambiarRolRequest(RolUsuario.OPERADOR, companyId);

        mockMvc.perform(put("/users/" + usuarioObjetivoId + "/role")
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cambio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("OPERADOR"));

        Usuario actualizado = usuarioRepository.findById(usuarioObjetivoId).orElseThrow();
        assertThat(actualizado.getRol()).isEqualTo(RolUsuario.OPERADOR);
        assertThat(actualizado.getCompanyId()).isEqualTo(companyId);
    }

    @Test
    void cambiarRolAPasajeroIgnoraElCompanyIdRecibido() throws Exception {
        CambiarRolRequest cambio = new CambiarRolRequest(RolUsuario.PASAJERO, UUID.randomUUID());

        mockMvc.perform(put("/users/" + usuarioObjetivoId + "/role")
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cambio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").doesNotExist());
    }

    @Test
    void obtenerElPropioUsuarioDevuelve200() throws Exception {
        String tokenPropio = jwtSigner.sign(usuarioObjetivoId, RolUsuario.PASAJERO, null, null, Duration.ofMinutes(5));

        mockMvc.perform(get("/users/" + usuarioObjetivoId)
                        .header("Authorization", "Bearer " + tokenPropio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuarioObjetivoId.toString()));
    }

    @Test
    void obtenerOtroUsuarioConTokenAdminDevuelve200() throws Exception {
        mockMvc.perform(get("/users/" + usuarioObjetivoId)
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuarioObjetivoId.toString()));
    }

    @ParameterizedTest
    @EnumSource(value = RolUsuario.class, names = {"PASAJERO", "CONDUCTOR", "OPERADOR"})
    void obtenerOtroUsuarioSinSerAdminDevuelve403ConForbidden(RolUsuario rol) throws Exception {
        mockMvc.perform(get("/users/" + usuarioObjetivoId)
                        .header("Authorization", "Bearer " + tokenPara(rol)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void obtenerUsuarioInexistenteConTokenAdminDevuelve404() throws Exception {
        mockMvc.perform(get("/users/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginYRegistroSiguenFuncionandoSinTokenDespuesDeAgregarElFiltro() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("sin.token@tripbus.com", "Passw0rd1", "CC-9002", "Sin Token");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("sin.token@tripbus.com", "Passw0rd1"))))
                .andExpect(status().isOk());
    }

    @Test
    void tokenVigenteDeUnaCuentaSuspendidaSeRechazaEnEndpointProtegidoYEnValidate() throws Exception {
        String token = tokenPara(RolUsuario.ADMIN);
        UUID adminId = usuarioRepository.findAll().stream()
                .filter(u -> u.getRol() == RolUsuario.ADMIN).findFirst().orElseThrow().getId();

        // Mientras la cuenta está activa el token funciona...
        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Usuario admin = usuarioRepository.findById(adminId).orElseThrow();
        admin.setActivo(false);
        usuarioRepository.save(admin);

        // ...y al suspenderla deja de valer aunque no haya vencido (mismo 403 que el login).
        mockMvc.perform(get("/users/" + usuarioObjetivoId).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        mockMvc.perform(post("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }
}
