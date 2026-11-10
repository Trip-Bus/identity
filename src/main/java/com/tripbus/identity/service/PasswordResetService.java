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
import com.tripbus.identity.util.EmailNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class PasswordResetService {

    /** Vigencia del enlace de recuperación (el correo la menciona: PlantillasCorreo). */
    static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final UsuarioRepository usuarioRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHasher passwordHasher;
    private final EmailSender emailSender;
    private final GeneradorTokens generadorTokens;
    private final CambioContrasenaPublisher cambioContrasenaPublisher;
    private final LoginAttemptService loginAttempts;
    private final RefreshTokenService refreshTokens;

    @Autowired
    public PasswordResetService(
            UsuarioRepository usuarioRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordHasher passwordHasher,
            EmailSender emailSender,
            GeneradorTokens generadorTokens,
            CambioContrasenaPublisher cambioContrasenaPublisher,
            LoginAttemptService loginAttempts,
            RefreshTokenService refreshTokens) {
        this.usuarioRepository = usuarioRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordHasher = passwordHasher;
        this.emailSender = emailSender;
        this.generadorTokens = generadorTokens;
        this.cambioContrasenaPublisher = cambioContrasenaPublisher;
        this.loginAttempts = loginAttempts;
        this.refreshTokens = refreshTokens;
    }

    public PasswordResetService(UsuarioRepository usuarioRepository, PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordHasher passwordHasher, EmailSender emailSender, GeneradorTokens generadorTokens,
            CambioContrasenaPublisher cambioContrasenaPublisher) {
        this(usuarioRepository, passwordResetTokenRepository, passwordHasher, emailSender, generadorTokens,
                cambioContrasenaPublisher, null, null);
    }

    @Transactional
    public void solicitarRecuperacion(String emailRecibido) {
        String email = EmailNormalizer.normalizar(emailRecibido);
        // Decisión de seguridad: si el email no existe, se retorna en silencio (sin
        // crear token ni lanzar excepción), para que el controlador responda EXACTAMENTE
        // igual en ambos casos y este endpoint no sirva para enumerar cuentas.
        usuarioRepository.findByEmail(email).ifPresent(usuario -> emitirYEnviarToken(usuario, email));
    }

    /**
     * US-103: una cuenta creada por un ADMIN no tiene contraseña conocida. Se le envía el mismo
     * enlace de recuperación para que la persona defina la suya.
     */
    @Transactional
    public void enviarEnlaceDefinirContrasena(Usuario usuario) {
        emitirYEnviarToken(usuario, usuario.getEmail());
    }

    @Transactional
    public void enviarAvisoBloqueo(Usuario usuario) {
        String tokenPlano=crearYGuardarToken(usuario);
        emailSender.enviarAvisoBloqueoCuenta(usuario.getEmail(), usuario.getNombre(), tokenPlano);
    }

    private void emitirYEnviarToken(Usuario usuario, String email) {
        String tokenPlano=crearYGuardarToken(usuario);
        // El token en texto plano solo se usa para el correo; nunca se persiste.
        emailSender.enviarCorreoRecuperacion(email, usuario.getNombre(), tokenPlano);
    }

    private String crearYGuardarToken(Usuario usuario) {
        String tokenPlano=generadorTokens.generar();
        Instant now = Instant.now();
        PasswordResetToken resetToken =
                new PasswordResetToken(usuario.getId(), generadorTokens.hash(tokenPlano), now, now.plus(TOKEN_TTL));
        passwordResetTokenRepository.save(resetToken);
        return tokenPlano;
    }

    @Transactional
    public void restablecerContrasena(String tokenPlano, String nuevaPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(generadorTokens.hash(tokenPlano))
                .orElseThrow(TokenInvalidoOExpiradoException::new);

        Instant now = Instant.now();
        if (resetToken.getUsedAt() != null || resetToken.getExpiresAt().isBefore(now)) {
            throw new TokenInvalidoOExpiradoException();
        }

        Usuario usuario = usuarioRepository.findById(resetToken.getUsuarioId())
                .orElseThrow(TokenInvalidoOExpiradoException::new);

        usuario.setPasswordHash(passwordHasher.hash(nuevaPassword));
        usuario.setPasswordChangedAt(now);
        // US-130: el enlace llegó a su correo, así que el correo es suyo (p. ej. OPERADOR/CONDUCTOR de US-103).
        if (!usuario.isEmailVerificado()) {
            usuario.setEmailVerificado(true);
            usuario.setEmailVerificadoEn(now);
        }
        usuarioRepository.save(usuario);
        if (loginAttempts != null) loginAttempts.clear(usuario.getEmail());

        resetToken.setUsedAt(now);
        passwordResetTokenRepository.save(resetToken);

        // US-129: los tokens anteriores ya no valen; el Realtime Gateway cierra las conexiones abiertas.
        cambioContrasenaPublisher.publicarCambio(usuario.getId(), now);
        if (refreshTokens != null) refreshTokens.revokeAll(usuario.getId());
    }
}
