package com.tripbus.identity.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sin GOOGLE_CLIENT_ID (lo normal en el perfil de pruebas) el inicio de sesión con Google está apagado. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleLoginDesactivadoTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void providersDiceQueGoogleEstaApagado() throws Exception {
        mockMvc.perform(get("/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.google.enabled").value(false))
                .andExpect(jsonPath("$.google.clientId").isEmpty());
    }

    @Test
    void postGoogleResponde404GoogleLoginDisabled() throws Exception {
        mockMvc.perform(post("/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"cualquier-cosa\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GOOGLE_LOGIN_DISABLED"));
    }
}
