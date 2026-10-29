package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.exception.CredencialesInvalidasException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.CuentaBloqueadaException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.CompletableFuture;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordHasher passwordHasher;
    private final JwtSigner jwtSigner;
    private final VerificacionEmailService verificacionEmailService;
    private final LoginAttemptService loginAttempts;
    private final RefreshTokenService refreshTokens;
    private final PasswordResetService passwordResetService;

    @Autowired
    public AuthService(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher, JwtSigner jwtSigner,
                       VerificacionEmailService verificacionEmailService, LoginAttemptService loginAttempts,
                       RefreshTokenService refreshTokens, PasswordResetService passwordResetService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordHasher = passwordHasher;
        this.jwtSigner = jwtSigner;
        this.verificacionEmailService = verificacionEmailService;
        this.loginAttempts = loginAttempts;
        this.refreshTokens = refreshTokens;
        this.passwordResetService = passwordResetService;
    }

    /** Compatibilidad con pruebas unitarias del flujo previo. */
    AuthService(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher, JwtSigner jwtSigner,
                VerificacionEmailService verificacionEmailService) {
        this(usuarioRepository, passwordHasher, jwtSigner, verificacionEmailService, null, null, null);
    }

    @Transactional
    public AuthResponse registrar(RegistroRequest request) {
        // Decisión de seguridad: a diferencia del login (donde nunca se revela si un
        // email existe, para no permitir enumerar cuentas ajenas), aquí el dato en
        // conflicto (email o documento) pertenece al propio solicitante: decirle cuál
        // de los dos ya está registrado es una mejora de UX estándar y no filtra
        // información sobre terceros. Por eso se usan dos excepciones/códigos
        // distintos en vez de uno genérico.
        if (usuarioRepository.existsByEmail(request.email())) {
            throw new EmailYaRegistradoException();
        }
        if (usuarioRepository.existsByDocumentoIdentidad(request.documentoIdentidad())) {
            throw new DocumentoYaRegistradoException();
        }

        String passwordHash = passwordHasher.hash(request.password());
        Usuario usuario = new Usuario(request.email(), passwordHash, request.documentoIdentidad(), request.nombre());
        try {
            // saveAndFlush: el INSERT se ejecuta aquí y no al confirmar, para poder traducir el choque.
            usuario = usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException ex) {
            // Registro concurrente: otra petición con el mismo email/documento pasó la verificación
            // de arriba al mismo tiempo y el índice único de la BD rechazó esta. Se responde 409 con
            // el mismo código que el duplicado normal en vez de un 500.
            throw ConflictosUnicidad.traducir(ex);
        }

        // US-130: el correo de verificación no bloquea el registro ni el login.
        verificacionEmailService.enviarVerificacion(usuario);
        return emitirRespuesta(usuario);
    }

    public AuthResponse login(LoginRequest request) {
        // Decisión de seguridad: usuario inexistente y contraseña incorrecta lanzan la
        // MISMA excepción con el MISMO mensaje, para no permitir que alguien deduzca,
        // por la respuesta, si un correo está registrado en el sistema.
        if (loginAttempts != null) {
            long retry = loginAttempts.remainingLockSeconds(request.email());
            if (retry > 0) throw new CuentaBloqueadaException(retry);
        }
        Usuario usuario = usuarioRepository.findByEmail(request.email()).orElse(null);
        if (usuario == null) {
            // Argon2 mantiene un costo similar al camino de una contraseña incorrecta.
            passwordHasher.hash(request.password());
            fallarLogin(request.email(), null);
            throw new CredencialesInvalidasException();
        }

        if (!passwordHasher.matches(request.password(), usuario.getPasswordHash())) {
            fallarLogin(request.email(), usuario);
            throw new CredencialesInvalidasException();
        }

        if (!usuario.isActivo()) {
            throw new CuentaSuspendidaException();
        }

        if (loginAttempts != null) loginAttempts.succeeded(request.email());
        return emitirRespuesta(usuario);
    }

    private void fallarLogin(String email, Usuario usuario) {
        if (loginAttempts != null && loginAttempts.failed(email) && usuario != null && passwordResetService != null) {
            CompletableFuture.runAsync(() -> passwordResetService.enviarAvisoBloqueo(usuario));
        }
        if (loginAttempts != null && loginAttempts.remainingLockSeconds(email) > 0) {
            throw new CuentaBloqueadaException(loginAttempts.remainingLockSeconds(email));
        }
    }

    /** También lo usa CuentaService: tras cambiar la contraseña la sesión actual sigue con un token nuevo. */
    AuthResponse emitirRespuesta(Usuario usuario) {
        Duration ttl = jwtSigner.getDefaultTtl();
        Instant expiresAt = Instant.now().plus(ttl);
        RefreshTokenService.Issued refresh = refreshTokens == null ? null : refreshTokens.issue(usuario.getId());
        String accessToken = jwtSigner.sign(usuario.getId(), usuario.getRol(), usuario.getCompanyId(),
                usuario.getEmail(), usuario.isEmailVerificado(), ttl);
        if (refresh == null) return new AuthResponse(accessToken, expiresAt, usuario.getId(), usuario.getRol());
        return new AuthResponse(accessToken, expiresAt, usuario.getId(), usuario.getRol(),
                refresh.token(), refresh.expiresAt());
    }
}
