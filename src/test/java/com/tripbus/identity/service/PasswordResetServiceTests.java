package com.tripbus.identity.service;

import com.tripbus.identity.events.CambioContrasenaPublisher;
import com.tripbus.identity.exception.TokenInvalidoOExpiradoException;
import com.tripbus.identity.model.PasswordResetToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.PasswordResetTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GeneradorTokens;
import com.tripbus.identity.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private EmailSender emailSender;

    @Mock
    private CambioContrasenaPublisher cambioContrasenaPublisher;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(
                usuarioRepository, passwordResetTokenRepository, passwordHasher, emailSender, new GeneradorTokens(),
                cambioContrasenaPublisher);
    }

    @Test
    void solicitarRecuperacionConEmailExistenteCreaUnTokenConTtlDeQuinceMinutosYEnviaElCorreo() {
        UUID userId = UUID.randomUUID();
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(userId);
        when(usuarioRepository.findByEmail("usuario@tripbus.com")).thenReturn(Optional.of(usuario));

        Instant antes = Instant.now();
        passwordResetService.solicitarRecuperacion("usuario@tripbus.com");
        Instant despues = Instant.now();

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());

        PasswordResetToken guardado = captor.getValue();
        assertThat(guardado.getUsuarioId()).isEqualTo(userId);
        assertThat(guardado.getUsedAt()).isNull();
        assertThat(guardado.getExpiresAt()).isBetween(
                antes.plus(Duration.ofMinutes(15)), despues.plus(Duration.ofMinutes(15)));

        verify(emailSender).enviarCorreoRecuperacion(eq("usuario@tripbus.com"), any(), anyString());
    }

    @Test
    void solicitarRecuperacionNormalizaElEmailAMinusculasSinEspacios() {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(UUID.randomUUID());
        when(usuarioRepository.findByEmail("ana@mail.com")).thenReturn(Optional.of(usuario));

        passwordResetService.solicitarRecuperacion("  Ana@Mail.COM ");

        verify(emailSender).enviarCorreoRecuperacion(eq("ana@mail.com"), any(), anyString());
    }

    @Test
    void avisoDeBloqueoGuardaElHashDeUnTokenYEnviaCorreoEspecifico() {
        UUID userId=UUID.randomUUID();
        Usuario usuario=mock(Usuario.class);
        when(usuario.getId()).thenReturn(userId);
        when(usuario.getEmail()).thenReturn("ana@mail.com");
        when(usuario.getNombre()).thenReturn("Ana");
        passwordResetService.enviarAvisoBloqueo(usuario);
        ArgumentCaptor<PasswordResetToken> captor=ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64);
        verify(emailSender).enviarAvisoBloqueoCuenta(eq("ana@mail.com"),eq("Ana"),anyString());
    }

    @Test
    void solicitarRecuperacionConEmailInexistenteNoCreaTokenNiEnviaCorreo() {
        when(usuarioRepository.findByEmail("noexiste@tripbus.com")).thenReturn(Optional.empty());

        passwordResetService.solicitarRecuperacion("noexiste@tripbus.com");

        verify(passwordResetTokenRepository, never()).save(any());
        verify(emailSender, never()).enviarCorreoRecuperacion(any(), any(), any());
    }

    @Test
    void restablecerContrasenaConTokenValidoActualizaLaContrasenaYMarcaElTokenComoUsado() {
        UUID usuarioId = UUID.randomUUID();

        PasswordResetToken resetToken = mock(PasswordResetToken.class);
        when(resetToken.getUsedAt()).thenReturn(null);
        when(resetToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofMinutes(10)));
        when(resetToken.getUsuarioId()).thenReturn(usuarioId);
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(resetToken));

        Usuario usuario = mock(Usuario.class);
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario));
        when(passwordHasher.hash("NuevaPass1")).thenReturn("nuevo-hash");

        passwordResetService.restablecerContrasena("token-plano", "NuevaPass1");

        verify(usuario).setPasswordHash("nuevo-hash");
        verify(usuario).setPasswordChangedAt(any(Instant.class));
        verify(usuarioRepository).save(usuario);
        verify(resetToken).setUsedAt(any(Instant.class));
        verify(passwordResetTokenRepository).save(resetToken);
        verify(usuario).setEmailVerificado(true);
        verify(cambioContrasenaPublisher).publicarCambio(any(), any(Instant.class));
    }

    @Test
    void restablecerContrasenaConTokenInexistenteLanzaExcepcion() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.restablecerContrasena("token-invalido", "NuevaPass1"))
                .isInstanceOf(TokenInvalidoOExpiradoException.class);
    }

    @Test
    void restablecerContrasenaConTokenYaUsadoLanzaExcepcion() {
        PasswordResetToken resetToken = mock(PasswordResetToken.class);
        when(resetToken.getUsedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(1)));
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> passwordResetService.restablecerContrasena("token-usado", "NuevaPass1"))
                .isInstanceOf(TokenInvalidoOExpiradoException.class);
    }

    @Test
    void restablecerContrasenaConTokenExpiradoLanzaExcepcion() {
        PasswordResetToken resetToken = mock(PasswordResetToken.class);
        when(resetToken.getUsedAt()).thenReturn(null);
        when(resetToken.getExpiresAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(1)));
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(resetToken));

        assertThatThrownBy(() -> passwordResetService.restablecerContrasena("token-expirado", "NuevaPass1"))
                .isInstanceOf(TokenInvalidoOExpiradoException.class);
    }
}
