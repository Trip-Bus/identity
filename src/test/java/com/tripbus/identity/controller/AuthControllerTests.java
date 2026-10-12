package com.tripbus.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTests {

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
    void registroExitosoDevuelve201YUnTokenVerificableConElJwks() throws Exception {
        RegistroRequest request =
                new RegistroRequest("nuevo.usuario@tripbus.com", "Passw0rd1", "CC-1001", "Nuevo Usuario");

        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        AuthResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        assertThat(response.rol()).isEqualTo(RolUsuario.PASAJERO);

        String jwksBody = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JWKSet jwkSet = JWKSet.parse(jwksBody);
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);
        PublicKey publicKey = rsaKey.toPublicKey();

        Jws<Claims> claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(response.accessToken());
        assertThat(claims.getPayload().getSubject()).isEqualTo(response.userId().toString());
        assertThat(claims.getPayload().get("role", String.class)).isEqualTo("PASAJERO");
        assertThat(claims.getPayload().get("email", String.class)).isEqualTo("nuevo.usuario@tripbus.com");
    }

    @Test
    void loginExitosoDevuelve200ConElToken() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("login.usuario@tripbus.com", "Passw0rd1", "CC-1002", "Login Usuario");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("login.usuario@tripbus.com", "Passw0rd1");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("PASAJERO"))
                .andReturn();

        AuthResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        Claims claims = Jwts.parser().verifyWith(publicKeyDelJwks()).build()
                .parseSignedClaims(response.accessToken()).getPayload();
        assertThat(claims.get("email", String.class)).isEqualTo("login.usuario@tripbus.com");
    }

    private PublicKey publicKeyDelJwks() throws Exception {
        String jwksBody = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((RSAKey) JWKSet.parse(jwksBody).getKeys().get(0)).toPublicKey();
    }

    @Test
    void registroConEmailDuplicadoDevuelve409ConElCodigoEsperado() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("duplicado@tripbus.com", "Passw0rd1", "CC-2001", "Persona Uno");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        RegistroRequest duplicado =
                new RegistroRequest("duplicado@tripbus.com", "Passw0rd1", "CC-2002", "Persona Dos");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicado)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registroConDocumentoDuplicadoDevuelve409ConElCodigoEsperado() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("persona.tres@tripbus.com", "Passw0rd1", "CC-3001", "Persona Tres");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        RegistroRequest duplicado =
                new RegistroRequest("persona.cuatro@tripbus.com", "Passw0rd1", "CC-3001", "Persona Cuatro");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicado)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOCUMENTO_ALREADY_REGISTERED"));
    }

    @Test
    void registroConDatosInvalidosDevuelve400ConValidationError() throws Exception {
        RegistroRequest invalido = new RegistroRequest("no-es-un-correo", "corta1", "", "");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalido)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void loginConCredencialesIncorrectasDevuelve401ConElCodigoEsperado() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("cred.usuario@tripbus.com", "Passw0rd1", "CC-4001", "Cred Usuario");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        LoginRequest loginIncorrecto = new LoginRequest("cred.usuario@tripbus.com", "ContrasenaIncorrecta1");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginIncorrecto)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginConCuentaSuspendidaDevuelve403ConElCodigoEsperado() throws Exception {
        RegistroRequest registro =
                new RegistroRequest("suspendido@tripbus.com", "Passw0rd1", "CC-5001", "Suspendido Usuario");
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registro)))
                .andExpect(status().isCreated());

        Usuario usuario = usuarioRepository.findByEmail("suspendido@tripbus.com").orElseThrow();
        usuario.setActivo(false);
        usuarioRepository.save(usuario);

        LoginRequest login = new LoginRequest("suspendido@tripbus.com", "Passw0rd1");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }

    private String registroJson(String email, String documento) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "email", email,
                "password", "Passw0rd1",
                "documentoIdentidad", documento,
                "nombre", "Ana",
                "aceptaPoliticaDatos", true));
    }

    @Test
    void emailSeNormalizaAMinusculasSinEspaciosEnRegistroLoginYDuplicados() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson("Ana@Mail.com ", "CC-5001")))
                .andExpect(status().isCreated());

        assertThat(usuarioRepository.findAll()).extracting(Usuario::getEmail).containsExactly("ana@mail.com");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@mail.com\",\"password\":\"Passw0rd1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" ANA@MAIL.COM\",\"password\":\"Passw0rd1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registroJson("aNa@mAIL.cOm", "CC-5002")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));

        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" Ana@MAIL.com \"}"))
                .andExpect(status().isAccepted());
    }
}
