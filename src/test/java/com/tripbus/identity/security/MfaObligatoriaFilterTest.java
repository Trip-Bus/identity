package com.tripbus.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** US-106: con la 2FA obligatoria para el rol, sin activarla solo se llega a /users/me/2fa/* y al logout. */
class MfaObligatoriaFilterTest {

    private final VerificacionDosPasosRepository repository = mock(VerificacionDosPasosRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID adminId = UUID.randomUUID();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void autenticar(RolUsuario rol) {
        UsuarioAutenticado usuario = new UsuarioAutenticado(adminId, rol, null);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(usuario, null, List.of()));
    }

    private MockHttpServletResponse ejecutar(Set<RolUsuario> obligatorios, String ruta, MockFilterChain chain)
            throws Exception {
        MfaObligatoriaFilter filtro = new MfaObligatoriaFilter(new PoliticaMfa(obligatorios, repository), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ruta);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, chain);
        return response;
    }

    @Test
    void adminSinDosPasosRecibe403MfaSetupRequiredFueraDeLasRutasDe2fa() throws Exception {
        autenticar(RolUsuario.ADMIN);
        when(repository.existsByUsuarioIdAndActivaTrue(adminId)).thenReturn(false);
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = ejecutar(Set.of(RolUsuario.ADMIN), "/users", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"MFA_SETUP_REQUIRED\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void lasRutasDe2faYElLogoutSiguenPermitidas() throws Exception {
        autenticar(RolUsuario.ADMIN);
        for (String ruta : List.of("/users/me/2fa/setup", "/users/me/2fa/enable", "/auth/logout")) {
            MockFilterChain chain = new MockFilterChain();
            assertThat(ejecutar(Set.of(RolUsuario.ADMIN), ruta, chain).getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void conLaDosPasosActivaPasaNormal() throws Exception {
        autenticar(RolUsuario.ADMIN);
        when(repository.existsByUsuarioIdAndActivaTrue(adminId)).thenReturn(true);
        MockFilterChain chain = new MockFilterChain();

        assertThat(ejecutar(Set.of(RolUsuario.ADMIN), "/users", chain).getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void otrosRolesSinAutenticarYSinRolesObligatoriosNoSeRevisan() throws Exception {
        autenticar(RolUsuario.PASAJERO);
        MockFilterChain pasajero = new MockFilterChain();
        assertThat(ejecutar(Set.of(RolUsuario.ADMIN), "/users/me", pasajero).getStatus()).isEqualTo(200);

        SecurityContextHolder.clearContext();
        MockFilterChain anonimo = new MockFilterChain();
        assertThat(ejecutar(Set.of(RolUsuario.ADMIN), "/auth/login", anonimo).getStatus()).isEqualTo(200);

        autenticar(RolUsuario.ADMIN);
        MockFilterChain sinPolitica = new MockFilterChain();
        assertThat(ejecutar(Set.of(), "/users", sinPolitica).getStatus()).isEqualTo(200);
        assertThat(ejecutar(null, "/users", new MockFilterChain()).getStatus()).isEqualTo(200);

        verifyNoInteractions(repository);
    }

    @Test
    void unPrincipalQueNoEsUnUsuarioDeIdentityNoSeRevisa() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("servicio-interno", null, List.of()));
        MockFilterChain chain = new MockFilterChain();

        assertThat(ejecutar(Set.of(RolUsuario.ADMIN), "/internal/users/1", chain).getStatus()).isEqualTo(200);
        verifyNoInteractions(repository);
    }
}
