package com.tripbus.identity.service;

import com.tripbus.identity.exception.TokenInvalidoOExpiradoException;
import com.tripbus.identity.model.PasswordResetToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.PasswordResetTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.util.EmailNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final int TOKEN_RANDOM_BYTES = 32;

    private final UsuarioRepository usuarioRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHasher passwordHasher;
    private final EmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(
            UsuarioRepository usuarioRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordHasher passwordHasher,
            EmailSender emailSender) {
        this.usuarioRepository = usuarioRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordHasher = passwordHasher;
        this.emailSender = emailSender;
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

    private void emitirYEnviarToken(Usuario usuario, String email) {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String tokenPlano = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        Instant now = Instant.now();
        PasswordResetToken resetToken =
                new PasswordResetToken(usuario.getId(), hashToken(tokenPlano), now, now.plus(TOKEN_TTL));
        passwordResetTokenRepository.save(resetToken);

        // El token en texto plano solo se usa para el correo; nunca se persiste.
        emailSender.enviarCorreoRecuperacion(email, tokenPlano);
    }

    @Transactional
    public void restablecerContrasena(String tokenPlano, String nuevaPassword) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(hashToken(tokenPlano))
                .orElseThrow(TokenInvalidoOExpiradoException::new);

        Instant now = Instant.now();
        if (resetToken.getUsedAt() != null || resetToken.getExpiresAt().isBefore(now)) {
            throw new TokenInvalidoOExpiradoException();
        }

        Usuario usuario = usuarioRepository.findById(resetToken.getUsuarioId())
                .orElseThrow(TokenInvalidoOExpiradoException::new);

        usuario.setPasswordHash(passwordHasher.hash(nuevaPassword));
        usuario.setPasswordChangedAt(now);
        usuarioRepository.save(usuario);

        resetToken.setUsedAt(now);
        passwordResetTokenRepository.save(resetToken);
    }

    private static String hashToken(String tokenPlano) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(tokenPlano.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
        }
    }
}
