package com.tripbus.identity.service;

import com.tripbus.identity.exception.DemasiadasSolicitudesException;
import com.tripbus.identity.exception.TokenInvalidoOExpiradoException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.EmailVerificationToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.EmailVerificationTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GeneradorTokens;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerificacionEmailServiceTests {

    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private EmailSender emailSender;

    private final GeneradorTokens generadorTokens = new GeneradorTokens();
    private VerificacionEmailService service;
    private final UUID usuarioId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VerificacionEmailService(usuarioRepository, tokenRepository, emailSender, generadorTokens);
    }

    private static Usuario usuario(boolean verificado) {
        Usuario u = new Usuario("ana@tripbus.co", "hash", "CC-1", "Ana Pérez");
        u.setEmailVerificado(verificado);
        return u;
    }

    @Test
    void enviarVerificacionGuardaSoloElHashConVigenciaDe24HorasYEnviaElCorreo() {
        Usuario u = usuario(false);
        Instant antes = Instant.now();

        service.enviarVerificacion(u);

        ArgumentCaptor<EmailVerificationToken> token = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(token.capture());
        ArgumentCaptor<String> tokenPlano = ArgumentCaptor.forClass(String.class);
        verify(emailSender).enviarCorreoVerificacion(eq("ana@tripbus.co"), eq("Ana Pérez"), tokenPlano.capture());

        assertThat(token.getValue().isReenvio()).isFalse();
        assertThat(token.getValue().getUsedAt()).isNull();
        assertThat(token.getValue().getTokenHash())
                .isEqualTo(generadorTokens.hash(tokenPlano.getValue()))
                .isNotEqualTo(tokenPlano.getValue());
        assertThat(token.getValue().getExpiresAt())
                .isBetween(antes.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    void reenviarConElCorreoYaVerificadoNoEnviaNada() {
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario(true)));

        assertThat(service.reenviar(usuarioId)).isFalse();

        verify(tokenRepository, never()).save(any());
        verify(emailSender, never()).enviarCorreoVerificacion(any(), any(), any());
    }

    @Test
    void reenviarPorDebajoDelLimiteEnviaUnTokenMarcadoComoReenvio() {
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario(false)));
        when(tokenRepository.countByUsuarioIdAndReenvioTrueAndCreatedAtAfter(eq(usuarioId), any())).thenReturn(2L);

        assertThat(service.reenviar(usuarioId)).isTrue();

        ArgumentCaptor<EmailVerificationToken> token = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(token.capture());
        assertThat(token.getValue().isReenvio()).isTrue();
        verify(emailSender).enviarCorreoVerificacion(eq("ana@tripbus.co"), eq("Ana Pérez"), anyString());
    }

    @Test
    void reenviarPorEncimaDelLimiteLanzaDemasiadasSolicitudesSinEnviar() {
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(usuario(false)));
        when(tokenRepository.countByUsuarioIdAndReenvioTrueAndCreatedAtAfter(eq(usuarioId), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.reenviar(usuarioId))
                .isInstanceOf(DemasiadasSolicitudesException.class)
                .hasMessageContaining("3");

        verify(emailSender, never()).enviarCorreoVerificacion(any(), any(), any());
    }

    @Test
    void reenviarAUnUsuarioInexistenteLanzaUsuarioNoEncontrado() {
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reenviar(usuarioId)).isInstanceOf(UsuarioNoEncontradoException.class);
    }

    private EmailVerificationToken tokenGuardado(String plano, Instant expira) {
        EmailVerificationToken token = new EmailVerificationToken(
                usuarioId, generadorTokens.hash(plano), Instant.now(), expira, false);
        when(tokenRepository.findByTokenHash(generadorTokens.hash(plano))).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void verificarConTokenValidoMarcaElCorreoYUsaElToken() {
        EmailVerificationToken token = tokenGuardado("abc", Instant.now().plus(Duration.ofHours(1)));
        Usuario u = usuario(false);
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(u));

        service.verificar("abc");

        assertThat(u.isEmailVerificado()).isTrue();
        assertThat(u.getEmailVerificadoEn()).isNotNull();
        assertThat(token.getUsedAt()).isNotNull();
        verify(usuarioRepository).save(u);
        verify(tokenRepository).save(token);
    }

    @Test
    void verificarConElCorreoYaVerificadoSoloUsaElToken() {
        EmailVerificationToken token = tokenGuardado("abc", Instant.now().plus(Duration.ofHours(1)));
        Usuario u = usuario(true);
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.of(u));

        service.verificar("abc");

        verify(usuarioRepository, never()).save(any());
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void verificarConTokenInexistenteFalla() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verificar("nada")).isInstanceOf(TokenInvalidoOExpiradoException.class);
    }

    @Test
    void verificarConTokenYaUsadoFalla() {
        EmailVerificationToken token = tokenGuardado("abc", Instant.now().plus(Duration.ofHours(1)));
        token.setUsedAt(Instant.now().minusSeconds(5));

        assertThatThrownBy(() -> service.verificar("abc")).isInstanceOf(TokenInvalidoOExpiradoException.class);
    }

    @Test
    void verificarConTokenVencidoFalla() {
        tokenGuardado("abc", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.verificar("abc")).isInstanceOf(TokenInvalidoOExpiradoException.class);
    }

    @Test
    void verificarConElUsuarioBorradoFalla() {
        tokenGuardado("abc", Instant.now().plus(Duration.ofHours(1)));
        when(usuarioRepository.findById(usuarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verificar("abc")).isInstanceOf(TokenInvalidoOExpiradoException.class);
    }
}
