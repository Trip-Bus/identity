package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-006: con la integración con Redis ENCENDIDA pero Redis caído (puerto sin servidor), el logout
 * responde igual (204) y el token queda revocado: la tabla sesiones_revocadas es la fuente de verdad.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "tripbus.events.enabled=true",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogoutConRedisCaidoTests {

    @DynamicPropertySource
    static void redisSinServidor(DynamicPropertyRegistry registry) throws Exception {
        // Un puerto libre (se abre y se cierra): nadie escucha ahí, así que Redis está "caído".
        try (ServerSocket socket = new ServerSocket(0)) {
            int puerto = socket.getLocalPort();
            registry.add("spring.data.redis.port", () -> puerto);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logoutConRedisCaidoResponde204YElTokenQuedaRevocado() throws Exception {
        String body = """
                {"email":"%s","password":"Passw0rd1","documentoIdentidad":"%s","nombre":"Ana","aceptaPoliticaDatos":true}
                """.formatted("redis.caido." + UUID.randomUUID() + "@tripbus.com", "CC-" + UUID.randomUUID());
        String registro = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(registro).get("accessToken").asText();

        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/validate").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
