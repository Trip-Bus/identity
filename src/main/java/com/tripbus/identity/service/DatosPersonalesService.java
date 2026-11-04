package com.tripbus.identity.service;

import com.tripbus.identity.dto.DatosPersonalesResponse;
import com.tripbus.identity.dto.DatosPersonalesResponse.ConsentimientoDato;
import com.tripbus.identity.dto.DatosPersonalesResponse.Perfil;
import com.tripbus.identity.dto.DatosPersonalesResponse.SesionActiva;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.UltimoAdminException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.AccionAuditoria;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * US-131 (Ley 1581 de 2012): la persona descarga todos sus datos o elimina su cuenta.
 *
 * <p>Eliminar no borra la fila (reservas y auditoría la referencian): la anonimiza, revoca todas las
 * sesiones, retira el consentimiento, borra la 2FA y avisa a los demás servicios con UserDeleted.
 */
@Service
public class DatosPersonalesService {

    static final String NOMBRE_ELIMINADO = "Usuario eliminado";
    static final String DOMINIO_ELIMINADO = "@tripbus.invalid";

    private static final Logger log = LoggerFactory.getLogger(DatosPersonalesService.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordHasher passwordHasher;
    private final SesionService sesionService;
    private final RefreshTokenService refreshTokens;
    private final MfaService mfa;
    private final AuditoriaService auditoria;
    private final SuspensionPublisher suspensionPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    public DatosPersonalesService(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher,
                                  SesionService sesionService, RefreshTokenService refreshTokens, MfaService mfa,
                                  AuditoriaService auditoria, SuspensionPublisher suspensionPublisher) {
        this.usuarioRepository = usuarioRepository;
        this.passwordHasher = passwordHasher;
        this.sesionService = sesionService;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.auditoria = auditoria;
        this.suspensionPublisher = suspensionPublisher;
    }

    /** GET /users/me/data: perfil, rol, empresa, consentimientos, sesiones abiertas y si tiene 2FA. */
    @Transactional(readOnly = true)
    public DatosPersonalesResponse exportar(UUID usuarioId) {
        Usuario u = buscar(usuarioId);
        Perfil perfil = new Perfil(u.getId(), u.getEmail(), u.getNombre(), u.getTelefono(), u.getDocumentoIdentidad(),
                u.getFechaCreacion(), u.getFechaActualizacion());
        var consentimientos = sesionService.consentimientos(usuarioId).stream()
                .map(c -> new ConsentimientoDato(c.getPolitica(), c.getVersion(), c.getAceptadoEn(), c.getOrigen(),
                        c.getRevocadoEn()))
                .toList();
        var sesiones = refreshTokens.sesionesActivas(usuarioId).stream()
                .map(t -> new SesionActiva(t.getFamilyId(), t.getCreatedAt(), t.getExpiresAt()))
                .toList();
        return new DatosPersonalesResponse(perfil, u.getRol(), u.getCompanyId(), u.isEmailVerificado(),
                u.getEmailVerificadoEn(), consentimientos, sesiones, mfa.activa(usuarioId), Instant.now());
    }

    /**
     * POST /users/me/delete. Verifica la contraseña; el único ADMIN activo no puede eliminarse (409
     * LAST_ADMIN). Todo ocurre en una transacción: anonimizar, revocar sesiones, retirar consentimientos,
     * borrar la 2FA y auditar. Redis (clave y evento) es "best effort" como el resto de eventos.
     */
    @Transactional
    public void eliminar(UUID usuarioId, String password) {
        Usuario usuario = buscar(usuarioId);
        if (!passwordHasher.matches(password, usuario.getPasswordHash())) {
            throw new ContrasenaActualIncorrectaException();
        }
        if (usuario.getRol() == RolUsuario.ADMIN && usuario.isActivo()
                && usuarioRepository.countByRolAndActivoTrue(RolUsuario.ADMIN) <= 1) {
            throw new UltimoAdminException();
        }

        Map<String, Object> antes = AuditoriaService.estado(usuario);
        Instant ahora = Instant.now();
        usuario.setEmail("deleted+" + UUID.randomUUID() + DOMINIO_ELIMINADO);
        usuario.setNombre(NOMBRE_ELIMINADO);
        usuario.setDocumentoIdentidad(null);
        usuario.setTelefono(null);
        usuario.setActivo(false);
        usuario.setEmailVerificado(false);
        usuario.setEmailVerificadoEn(null);
        // Nadie conoce la nueva contraseña y los tokens emitidos antes de este segundo dejan de valer.
        usuario.setPasswordHash(passwordHasher.hash(secretoAleatorio()));
        usuario.setPasswordChangedAt(ahora);
        usuarioRepository.save(usuario);

        refreshTokens.revokeAll(usuarioId);
        sesionService.revocarConsentimientos(usuarioId);
        mfa.eliminarDatos(usuarioId);

        Map<String, Object> despues = AuditoriaService.estado(usuario);
        despues.put("anonimizada", true);
        auditoria.registrar(usuarioId, AccionAuditoria.ELIMINAR_CUENTA, usuarioId, antes, despues, null);

        suspensionPublisher.publicarEliminacion(usuarioId);
        log.info("Usuario {} eliminó su cuenta (datos anonimizados)", usuarioId);
    }

    private Usuario buscar(UUID usuarioId) {
        return usuarioRepository.findById(usuarioId).orElseThrow(UsuarioNoEncontradoException::new);
    }

    private String secretoAleatorio() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }}
