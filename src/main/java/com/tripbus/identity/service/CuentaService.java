package com.tripbus.identity.service;

import com.tripbus.identity.dto.ActualizarPerfilRequest;
import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.CambiarPasswordRequest;
import com.tripbus.identity.dto.PerfilResponse;
import com.tripbus.identity.events.CambioContrasenaPublisher;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** La cuenta del propio usuario autenticado (/users/me): perfil (US-128) y cambio de contraseña (US-129). */
@Service
public class CuentaService {

    private static final Logger log = LoggerFactory.getLogger(CuentaService.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordHasher passwordHasher;
    private final AuthService authService;
    private final CambioContrasenaPublisher cambioContrasenaPublisher;
    private final EmailSender emailSender;
    private final RefreshTokenService refreshTokens;

    @Autowired
    public CuentaService(UsuarioRepository usuarioRepository,
                         PasswordHasher passwordHasher,
                         AuthService authService,
                         CambioContrasenaPublisher cambioContrasenaPublisher,
                         EmailSender emailSender, RefreshTokenService refreshTokens) {
        this.usuarioRepository = usuarioRepository;
        this.passwordHasher = passwordHasher;
        this.authService = authService;
        this.cambioContrasenaPublisher = cambioContrasenaPublisher;
        this.emailSender = emailSender;
        this.refreshTokens = refreshTokens;
    }

    public CuentaService(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher, AuthService authService,
                         CambioContrasenaPublisher cambioContrasenaPublisher, EmailSender emailSender) {
        this(usuarioRepository, passwordHasher, authService, cambioContrasenaPublisher, emailSender, null);
    }

    @Transactional(readOnly = true)
    public PerfilResponse obtenerPerfil(UUID usuarioId) {
        return toPerfil(buscar(usuarioId));
    }

    @Transactional
    public PerfilResponse actualizarPerfil(UUID usuarioId, ActualizarPerfilRequest request) {
        Usuario usuario = buscar(usuarioId);
        usuario.setNombre(request.nombre());
        usuario.setTelefono(request.telefono());
        return toPerfil(usuarioRepository.save(usuario));
    }

    /**
     * Verifica la contraseña actual (Argon2id), guarda la nueva y actualiza passwordChangedAt: desde ese
     * segundo los tokens emitidos antes dejan de valer (JwtAuthenticationFilter, también en /auth/validate).
     * Devuelve un token nuevo para que la sesión actual siga abierta.
     */
    @Transactional
    public AuthResponse cambiarContrasena(UUID usuarioId, CambiarPasswordRequest request) {
        Usuario usuario = buscar(usuarioId);
        if (!passwordHasher.matches(request.passwordActual(), usuario.getPasswordHash())) {
            throw new ContrasenaActualIncorrectaException();
        }

        Instant now = Instant.now();
        usuario.setPasswordHash(passwordHasher.hash(request.passwordNueva()));
        usuario.setPasswordChangedAt(now);
        usuario = usuarioRepository.save(usuario);
        if (refreshTokens != null) refreshTokens.revokeAll(usuarioId);

        cambioContrasenaPublisher.publicarCambio(usuarioId, now);
        emailSender.enviarAvisoCambioContrasena(usuario.getEmail(), usuario.getNombre());
        log.info("Usuario {} cambió su contraseña", usuarioId);
        return authService.emitirRespuesta(usuario);
    }

    private Usuario buscar(UUID usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(UsuarioNoEncontradoException::new);
    }

    private static PerfilResponse toPerfil(Usuario usuario) {
        return new PerfilResponse(
                usuario.getId(),
                usuario.getEmail(),
                usuario.isEmailVerificado(),
                usuario.getNombre(),
                usuario.getTelefono(),
                usuario.getDocumentoIdentidad(),
                usuario.getRol(),
                usuario.getCompanyId(),
                usuario.getFechaCreacion());
    }
}
