package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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

    private String tokenPara(RolUsuario rol) {
        return jwtSigner.sign(UUID.randomUUID(), rol, null, Duration.ofMinutes(5));
    }

    @Test
    void requestSinTokenAUnEndpointProtegidoDevuelve401ConUnauthenticated() throws Exception {
        mockMvc.perform(get("/users/" + usuarioObjetivoId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void requestConTokenExpiradoDevuelve401ConUnauthenticated() throws Exception {
        String tokenExpirado = jwtSigner.sign(UUID.randomUUID(), RolUsuario.ADMIN, null, Duration.ofMinutes(-5));

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
    void obtenerUsuarioConCualquierRolAutenticadoDevuelve200() throws Exception {
        mockMvc.perform(get("/users/" + usuarioObjetivoId)
                        .header("Authorization", "Bearer " + tokenPara(RolUsuario.CONDUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuarioObjetivoId.toString()));
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
}
