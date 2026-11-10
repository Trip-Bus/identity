package com.tripbus.identity.controller;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "tripbus.internal-token=token-interno-test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUsuarioControllerTests {

    private static final String HEADER = "X-Internal-Token";
    private static final String TOKEN = "token-interno-test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private JwtSigner jwtSigner;

    private Usuario conductor;

    @BeforeEach
    void setUp() {
        conductor = new Usuario("cond." + UUID.randomUUID() + "@tripbus.co", "hash-secreto",
                "CC-" + UUID.randomUUID(), "Conductor Interno");
        conductor.setRol(RolUsuario.CONDUCTOR);
        conductor.setCompanyId(UUID.randomUUID());
        conductor = usuarioRepository.save(conductor);
    }

    @Test
    void conTokenValidoDevuelveSoloIdNombreRolEmpresaYActivo() throws Exception {
        String body = mockMvc.perform(get("/internal/users/" + conductor.getId()).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conductor.getId().toString()))
                .andExpect(jsonPath("$.nombre").value("Conductor Interno"))
                .andExpect(jsonPath("$.rol").value("CONDUCTOR"))
                .andExpect(jsonPath("$.companyId").value(conductor.getCompanyId().toString()))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.documentoIdentidad").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(conductor.getEmail()).doesNotContain("hash-secreto")
                .doesNotContain(conductor.getDocumentoIdentidad());
    }

    @Test
    void usuarioSuspendidoSeDevuelveConActivoFalse() throws Exception {
        conductor.setActivo(false);
        usuarioRepository.save(conductor);

        mockMvc.perform(get("/internal/users/" + conductor.getId()).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    @Test
    void usuarioInexistenteDa404() throws Exception {
        mockMvc.perform(get("/internal/users/" + UUID.randomUUID()).header(HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(get("/internal/users/" + conductor.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void conTokenIncorrectoDa401() throws Exception {
        mockMvc.perform(get("/internal/users/" + conductor.getId()).header(HEADER, "otro-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unJwtDeUsuarioAunqueSeaAdminNoBastaParaLosEndpointsInternos() throws Exception {
        Usuario admin = new Usuario("admin." + UUID.randomUUID() + "@tripbus.co", "hash",
                "CC-" + UUID.randomUUID(), "Admin");
        admin.setRol(RolUsuario.ADMIN);
        admin = usuarioRepository.save(admin);
        String jwt = jwtSigner.sign(admin.getId(), RolUsuario.ADMIN, null, admin.getEmail(), false, Duration.ofMinutes(5));

        mockMvc.perform(get("/internal/users/" + conductor.getId()).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void elTokenInternoNoAutenticaFueraDeInternal() throws Exception {
        mockMvc.perform(get("/users/" + conductor.getId()).header(HEADER, TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
