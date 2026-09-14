package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.exception.CredencialesInvalidasException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private JwtSigner jwtSigner;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(usuarioRepository, passwordHasher, jwtSigner);
    }

    @Test
    void registrarCreaUnUsuarioConRolPasajeroYDevuelveElToken() {
        RegistroRequest request = new RegistroRequest("nueva@tripbus.com", "Passw0rd!", "CC-123", "Nueva Persona");
        UUID userId = UUID.randomUUID();

        when(usuarioRepository.existsByEmail(request.email())).thenReturn(false);
        when(usuarioRepository.existsByDocumentoIdentidad(request.documentoIdentidad())).thenReturn(false);
        when(passwordHasher.hash(request.password())).thenReturn("hash-argon2id");

        Usuario usuarioGuardado = mock(Usuario.class);
        when(usuarioGuardado.getId()).thenReturn(userId);
        when(usuarioGuardado.getRol()).thenReturn(RolUsuario.PASAJERO);
        when(usuarioRepository.save(any(Usuario.class))).thenReturn(usuarioGuardado);

        when(jwtSigner.getDefaultTtl()).thenReturn(Duration.ofMinutes(60));
        when(jwtSigner.sign(eq(userId), eq(RolUsuario.PASAJERO), isNull(), any(Duration.class)))
                .thenReturn("token-firmado");

        AuthResponse response = authService.registrar(request);

        assertThat(response.accessToken()).isEqualTo("token-firmado");
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.rol()).isEqualTo(RolUsuario.PASAJERO);

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        assertThat(usuarioCaptor.getValue().getRol()).isEqualTo(RolUsuario.PASAJERO);
        assertThat(usuarioCaptor.getValue().getPasswordHash()).isEqualTo("hash-argon2id");
    }

    @Test
    void registrarConEmailDuplicadoLanzaExcepcionYNoGuardaNada() {
        RegistroRequest request = new RegistroRequest("existe@tripbus.com", "Passw0rd!", "CC-999", "Alguien");
        when(usuarioRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.registrar(request))
                .isInstanceOf(EmailYaRegistradoException.class);

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void registrarConDocumentoDuplicadoLanzaExcepcionYNoGuardaNada() {
        RegistroRequest request = new RegistroRequest("otra@tripbus.com", "Passw0rd!", "CC-001", "Otra Persona");
        when(usuarioRepository.existsByEmail(request.email())).thenReturn(false);
        when(usuarioRepository.existsByDocumentoIdentidad(request.documentoIdentidad())).thenReturn(true);

        assertThatThrownBy(() -> authService.registrar(request))
                .isInstanceOf(DocumentoYaRegistradoException.class);

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void loginExitosoDevuelveElToken() {
        LoginRequest request = new LoginRequest("usuario@tripbus.com", "Passw0rd!");
        UUID userId = UUID.randomUUID();

        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(userId);
        when(usuario.getPasswordHash()).thenReturn("hash-almacenado");
        when(usuario.isActivo()).thenReturn(true);
        when(usuario.getRol()).thenReturn(RolUsuario.PASAJERO);

        when(usuarioRepository.findByEmail(request.email())).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches(request.password(), "hash-almacenado")).thenReturn(true);
        when(jwtSigner.getDefaultTtl()).thenReturn(Duration.ofMinutes(60));
        when(jwtSigner.sign(eq(userId), eq(RolUsuario.PASAJERO), isNull(), any(Duration.class)))
                .thenReturn("token-login");

        AuthResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("token-login");
        assertThat(response.userId()).isEqualTo(userId);
    }

    @Test
    void loginConContrasenaIncorrectaLanzaCredencialesInvalidas() {
        LoginRequest request = new LoginRequest("usuario@tripbus.com", "incorrecta");
        Usuario usuario = mock(Usuario.class);
        when(usuario.getPasswordHash()).thenReturn("hash-almacenado");

        when(usuarioRepository.findByEmail(request.email())).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches(request.password(), "hash-almacenado")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CredencialesInvalidasException.class);
    }

    @Test
    void loginConEmailInexistenteLanzaLaMismaExcepcionQueContrasenaIncorrecta() {
        LoginRequest request = new LoginRequest("noexiste@tripbus.com", "cualquiera");
        when(usuarioRepository.findByEmail(request.email())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CredencialesInvalidasException.class);
    }

    @Test
    void loginConCuentaSuspendidaLanzaCuentaSuspendida() {
        LoginRequest request = new LoginRequest("suspendido@tripbus.com", "Passw0rd!");
        Usuario usuario = mock(Usuario.class);
        when(usuario.getPasswordHash()).thenReturn("hash-almacenado");
        when(usuario.isActivo()).thenReturn(false);

        when(usuarioRepository.findByEmail(request.email())).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches(request.password(), "hash-almacenado")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CuentaSuspendidaException.class);
    }
}
