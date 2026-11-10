package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.MfaLoginRequest;
import com.tripbus.identity.dto.ResultadoLogin;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.MfaException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** US-106: el login con la 2FA activa no entrega tokens hasta el segundo paso. */
class AuthServiceMfaTests {

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final JwtSigner jwtSigner = mock(JwtSigner.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final MfaService mfa = mock(MfaService.class);
    private final UUID userId = UUID.randomUUID();
    private Usuario usuario;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(usuarioRepository, passwordHasher, jwtSigner,
                mock(VerificacionEmailService.class), null, refreshTokens, null, mfa);
        usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(userId);
        when(usuario.getRol()).thenReturn(RolUsuario.ADMIN);
        when(usuario.getPasswordHash()).thenReturn("hash");
        when(usuario.isActivo()).thenReturn(true);
        when(usuarioRepository.findByEmail("admin@tripbus.co")).thenReturn(Optional.of(usuario));
        when(usuarioRepository.findById(userId)).thenReturn(Optional.of(usuario));
        when(passwordHasher.matches("Passw0rd1", "hash")).thenReturn(true);
        when(jwtSigner.getDefaultTtl()).thenReturn(Duration.ofMinutes(60));
        when(jwtSigner.sign(any(), any(), any(), any(), anyBoolean(), any())).thenReturn("jwt");
        when(refreshTokens.issue(userId)).thenReturn(new RefreshTokenService.Issued("refresh", Instant.now()));
    }

    @Test
    void conLaDosPasosActivaElLoginEntregaSoloElMfaToken() {
        when(mfa.activa(userId)).thenReturn(true);
        when(mfa.emitirDesafio(userId)).thenReturn("mfa-token");

        ResultadoLogin resultado = authService.iniciarSesion(new LoginRequest("admin@tripbus.co", "Passw0rd1"));

        assertThat(resultado.tokens()).isNull();
        assertThat(resultado.mfa().mfaRequired()).isTrue();
        assertThat(resultado.mfa().mfaToken()).isEqualTo("mfa-token");
        verify(refreshTokens, never()).issue(any());
        verify(jwtSigner, never()).sign(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void elLoginDirectoNoSirveConLaDosPasosActiva() {
        when(mfa.activa(userId)).thenReturn(true);
        when(mfa.emitirDesafio(userId)).thenReturn("mfa-token");

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin@tripbus.co", "Passw0rd1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinLaDosPasosElLoginEntregaLosTokens() {
        when(mfa.activa(userId)).thenReturn(false);

        ResultadoLogin resultado = authService.iniciarSesion(new LoginRequest("admin@tripbus.co", "Passw0rd1"));

        assertThat(resultado.mfa()).isNull();
        assertThat(resultado.tokens().accessToken()).isEqualTo("jwt");
        assertThat(resultado.tokens().refreshToken()).isEqualTo("refresh");
    }

    @Test
    void elSegundoPasoCorrectoEntregaLosTokens() {
        when(mfa.verificarDesafio("mfa-token", "123456", null)).thenReturn(userId);

        AuthResponse r = authService.completarSegundoPaso(new MfaLoginRequest("mfa-token", "123456", null));

        assertThat(r.accessToken()).isEqualTo("jwt");
        assertThat(r.userId()).isEqualTo(userId);
    }

    @Test
    void elSegundoPasoDeUnaCuentaSuspendidaOInexistenteNoEntregaTokens() {
        when(mfa.verificarDesafio(anyString(), any(), any())).thenReturn(userId);
        when(usuario.isActivo()).thenReturn(false);

        assertThatThrownBy(() -> authService.completarSegundoPaso(new MfaLoginRequest("t", "123456", null)))
                .isInstanceOf(CuentaSuspendidaException.class);

        when(usuarioRepository.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.completarSegundoPaso(new MfaLoginRequest("t", "123456", null)))
                .isInstanceOf(UsuarioNoEncontradoException.class);
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    void sinServicioDeDosPasosElSegundoPasoNoAplica() {
        AuthService sinMfa = new AuthService(usuarioRepository, passwordHasher, jwtSigner,
                mock(VerificacionEmailService.class));

        assertThatThrownBy(() -> sinMfa.completarSegundoPaso(new MfaLoginRequest("t", "123456", null)))
                .isInstanceOf(MfaException.class).hasFieldOrPropertyWithValue("code", "MFA_TOKEN_INVALID");
    }
}
