package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US-089: confirma que las respuestas de /auth/** incluyen las cabeceras de seguridad HTTP
 * esperadas (SecurityConfig#securityFilterChain).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @BeforeEach
    void limpiarBaseDeDatos() {
        usuarioRepository.deleteAll();
    }

    @Test
    void loginResponseIncludesTheExpectedSecurityHeaders() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("headers.usuario@tripbus.com", "Passw0rd1", "CC-7001", "Headers Usuario");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("headers.usuario@tripbus.com", "Passw0rd1");

        // .secure(true) simula que la petición ya llegó como HTTPS (o fue reescrita así por
        // ForwardedHeaderFilter a partir de X-Forwarded-Proto), que es cuando Spring Security
        // añade Strict-Transport-Security.
        mockMvc.perform(post("/auth/login")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
