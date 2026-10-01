package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.SesionRevocadaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US-001 (consentimiento), US-096 (logout) y /auth/validate para el API Gateway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SesionYConsentimientoTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private ConsentimientoRepository consentimientoRepository;
    @Autowired
    private SesionRevocadaRepository sesionRevocadaRepository;

    @BeforeEach
    void limpiar() {
        sesionRevocadaRepository.deleteAll();
        consentimientoRepository.deleteAll();
    }

    private JsonNode registrar(String email, String documento) throws Exception {
        String body = """
                {"email":"%s","password":"Passw0rd1","documentoIdentidad":"%s","nombre":"Ana","aceptaPoliticaDatos":true}
                """.formatted(email, documento);
        String response = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void elRegistroExigeAceptarLaPoliticaDeDatosYLaDejaRegistrada() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"sin.consentimiento@tripbus.com","password":"Passw0rd1",
                         "documentoIdentidad":"CC-7001","nombre":"Sin","aceptaPoliticaDatos":false}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.aceptaPoliticaDatos").exists());

        JsonNode registro = registrar("con.consentimiento@tripbus.com", "CC-7002");
        UUID userId = UUID.fromString(registro.get("userId").asText());
        assertThat(consentimientoRepository.findByUsuarioId(userId)).hasSize(1);
    }

    @Test
    void validateDevuelveLaIdentidadYTrasElLogoutElTokenQuedaRevocado() throws Exception {
        JsonNode registro = registrar("logout." + UUID.randomUUID() + "@tripbus.com", "CC-" + UUID.randomUUID());
        String token = registro.get("accessToken").asText();

        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-User-Role", "PASAJERO"))
                .andExpect(jsonPath("$.valido").value(true));

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/users/" + registro.get("userId").asText()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unUsuarioSoloVeSusPropiosRoles() throws Exception {
        JsonNode a = registrar("roles.a." + UUID.randomUUID() + "@tripbus.com", "CC-" + UUID.randomUUID());
        JsonNode b = registrar("roles.b." + UUID.randomUUID() + "@tripbus.com", "CC-" + UUID.randomUUID());
        String tokenA = a.get("accessToken").asText();
        mockMvc.perform(get("/users/" + a.get("userId").asText() + "/roles").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("PASAJERO"));
        mockMvc.perform(get("/users/" + b.get("userId").asText() + "/roles").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }
}
