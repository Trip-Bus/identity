package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.Totp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-106: con required-roles=ADMIN, un ADMIN sin 2FA solo puede configurarla; los demás roles siguen igual. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "tripbus.identity.mfa.required-roles=ADMIN")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaObligatoriaControllerTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private VerificacionDosPasosRepository verificaciones;
    @Autowired
    private CifradorSecretos cifrador;
    @Autowired
    private JwtSigner jwtSigner;

    private Usuario admin;
    private String tokenAdmin;

    @BeforeEach
    void setUp() {
        admin = guardar(RolUsuario.ADMIN);
        tokenAdmin = token(admin);
    }

    private Usuario guardar(RolUsuario rol) {
        String sufijo = UUID.randomUUID().toString();
        Usuario u = new Usuario("req." + sufijo + "@tripbus.co", "hash", "CC-" + sufijo, "Persona");
        u.setRol(rol);
        return usuarioRepository.saveAndFlush(u);
    }

    private String token(Usuario u) {
        return "Bearer " + jwtSigner.sign(u.getId(), u.getRol(), null, u.getEmail(), false, Duration.ofMinutes(5));
    }

    @Test
    void unAdminSinDosPasosSoloPuedeConfigurarlaYAlActivarlaRecuperaElAcceso() throws Exception {
        mockMvc.perform(get("/users").header("Authorization", tokenAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MFA_SETUP_REQUIRED"));
        mockMvc.perform(get("/users/me").header("Authorization", tokenAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MFA_SETUP_REQUIRED"));

        mockMvc.perform(post("/users/me/2fa/setup").header("Authorization", tokenAdmin))
                .andExpect(status().isOk());
        byte[] secreto = cifrador.descifrar(verificaciones.findById(admin.getId()).orElseThrow().getSecretoCifrado(),
                admin.getId().toString());
        mockMvc.perform(post("/users/me/2fa/enable").header("Authorization", tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", Totp.codigo(secreto, Totp.paso(Instant.now()))))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users").header("Authorization", tokenAdmin)).andExpect(status().isOk());
    }

    @Test
    void losRolesQueNoEstanEnLaListaNoSeAfectan() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", token(guardar(RolUsuario.OPERADOR))))
                .andExpect(status().isOk());
    }

    @Test
    void unAdminSinDosPasosPuedeCerrarSesion() throws Exception {
        mockMvc.perform(post("/auth/logout").header("Authorization", tokenAdmin))
                .andExpect(status().isNoContent());
    }
}
