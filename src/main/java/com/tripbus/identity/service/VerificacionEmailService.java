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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * US-130: verificación del correo. Al registrarse se envía un enlace de un solo uso (24 h); el usuario
 * autenticado puede pedir que se reenvíe hasta {@value #MAXIMO_REENVIOS_POR_HORA} veces por hora.
 * No verificar el correo no bloquea el login: solo se refleja en el claim emailVerificado y en /users/me.
 */
@Service
public class VerificacionEmailService {

    /** Vigencia del enlace de verificación (el correo la menciona: PlantillasCorreo). */
    static final Duration TOKEN_TTL = Duration.ofHours(24);
    static final int MAXIMO_REENVIOS_POR_HORA = 3;

    private static final Duration VENTANA_REENVIOS = Duration.ofHours(1);

    private final UsuarioRepository usuarioRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailSender emailSender;
    private final GeneradorTokens generadorTokens;

    public VerificacionEmailService(UsuarioRepository usuarioRepository,
                                    EmailVerificationTokenRepository tokenRepository,
                                    EmailSender emailSender,
                                    GeneradorTokens generadorTokens) {
        this.usuarioRepository = usuarioRepository;
        this.tokenRepository = tokenRepository;
        this.emailSender = emailSender;
        this.generadorTokens = generadorTokens;
    }

    /** Correo de verificación del registro (no cuenta para el límite de reenvíos). */
    @Transactional
    public void enviarVerificacion(Usuario usuario) {
        emitirYEnviar(usuario, false, Instant.now());
    }

    /**
     * Reenvío pedido por el usuario. Si el correo ya está verificado no se envía nada.
     *
     * @return false si el correo ya estaba verificado
     * @throws DemasiadasSolicitudesException si ya pidió {@value #MAXIMO_REENVIOS_POR_HORA} en la última hora
     */
    @Transactional
    public boolean reenviar(UUID usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId).orElseThrow(UsuarioNoEncontradoException::new);
        if (usuario.isEmailVerificado()) {
            return false;
        }
        Instant now = Instant.now();
        long recientes = tokenRepository.countByUsuarioIdAndReenvioTrueAndCreatedAtAfter(
                usuarioId, now.minus(VENTANA_REENVIOS));
        if (recientes >= MAXIMO_REENVIOS_POR_HORA) {
            throw new DemasiadasSolicitudesException(
                    "Ya pediste " + MAXIMO_REENVIOS_POR_HORA + " correos de verificación en la última hora. "
                            + "Intenta más tarde");
        }
        emitirYEnviar(usuario, true, now);
        return true;
    }

    @Transactional
    public void verificar(String tokenPlano) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(generadorTokens.hash(tokenPlano))
                .orElseThrow(TokenInvalidoOExpiradoException::new);

        Instant now = Instant.now();
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw new TokenInvalidoOExpiradoException();
        }

        Usuario usuario = usuarioRepository.findById(token.getUsuarioId())
                .orElseThrow(TokenInvalidoOExpiradoException::new);
        if (!usuario.isEmailVerificado()) {
            usuario.setEmailVerificado(true);
            usuario.setEmailVerificadoEn(now);
            usuarioRepository.save(usuario);
        }

        token.setUsedAt(now);
        tokenRepository.save(token);
    }

    private void emitirYEnviar(Usuario usuario, boolean reenvio, Instant now) {
        String tokenPlano = generadorTokens.generar();
        tokenRepository.save(new EmailVerificationToken(
                usuario.getId(), generadorTokens.hash(tokenPlano), now, now.plus(TOKEN_TTL), reenvio));
        // El token en texto plano solo se usa para el correo; nunca se persiste.
        emailSender.enviarCorreoVerificacion(usuario.getEmail(), usuario.getNombre(), tokenPlano);
    }
}
