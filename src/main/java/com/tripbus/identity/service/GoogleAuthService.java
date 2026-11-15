package com.tripbus.identity.service;

import com.tripbus.identity.dto.GoogleLoginRequest;
import com.tripbus.identity.dto.ResultadoLogin;
import com.tripbus.identity.exception.CuentaBloqueadaException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.GoogleCuentaVinculadaException;
import com.tripbus.identity.exception.GoogleLoginDesactivadoException;
import com.tripbus.identity.exception.GoogleRegistroIncompletoException;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GoogleIdTokenVerifier;
import com.tripbus.identity.security.GoogleIdTokenVerifier.IdentidadGoogle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * POST /auth/google: inicio de sesión con el ID token de Google Identity Services. Reutiliza el flujo del
 * login normal ({@link AuthService#resultadoDe}): mismas reglas de bloqueo, suspensión y 2FA.
 *
 * <ol>
 *   <li>Usuario con ese {@code sub} de Google → inicia sesión.</li>
 *   <li>Usuario con el mismo correo → se vincula el {@code sub}, se marca el correo verificado (Google ya
 *       lo verificó) e inicia sesión.</li>
 *   <li>Nadie → registro de un PASAJERO sin contraseña, si viene la política aceptada y el documento; si no,
 *       422 GOOGLE_REGISTRO_INCOMPLETO con el correo y el nombre del token.</li>
 * </ol>
 */
@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    /** {@code registrado} es el usuario creado en esta petición (null si ya existía). */
    public record Resultado(ResultadoLogin login, Usuario registrado) {
    }

    private final GoogleIdTokenVerifier verifier;
    private final UsuarioRepository usuarioRepository;
    private final AuthService authService;
    private final LoginAttemptService loginAttempts;
    private final SesionService sesionService;

    public GoogleAuthService(GoogleIdTokenVerifier verifier, UsuarioRepository usuarioRepository,
                             AuthService authService, LoginAttemptService loginAttempts,
                             SesionService sesionService) {
        this.verifier = verifier;
        this.usuarioRepository = usuarioRepository;
        this.authService = authService;
        this.loginAttempts = loginAttempts;
        this.sesionService = sesionService;
    }

    @Transactional
    public Resultado iniciarSesion(GoogleLoginRequest request) {
        if (!verifier.habilitado()) {
            throw new GoogleLoginDesactivadoException();
        }
        IdentidadGoogle identidad = verifier.verificar(request.idToken());

        Usuario usuario = usuarioRepository.findByGoogleSub(identidad.sub())
                .or(() -> usuarioRepository.findByEmail(identidad.email()))
                .orElse(null);
        if (usuario == null) {
            Usuario nuevo = registrar(identidad, request);
            return new Resultado(authService.resultadoDe(nuevo), nuevo);
        }
        validarAcceso(usuario);
        vincular(usuario, identidad.sub());
        loginAttempts.succeeded(usuario.getEmail());
        return new Resultado(authService.resultadoDe(usuario), null);
    }

    /** Mismo orden que el login con contraseña: primero el bloqueo, luego la suspensión. */
    private void validarAcceso(Usuario usuario) {
        long retry = loginAttempts.remainingLockSeconds(usuario.getEmail());
        if (retry > 0) {
            throw new CuentaBloqueadaException(retry);
        }
        if (!usuario.isActivo()) {
            throw new CuentaSuspendidaException();
        }
    }

    private void vincular(Usuario usuario, String sub) {
        if (sub.equals(usuario.getGoogleSub())) {
            return;
        }
        if (usuario.getGoogleSub() != null) {
            // El correo pasó a otra cuenta de Google (p. ej. una cuenta de Workspace recreada): no se reemplaza.
            throw new GoogleCuentaVinculadaException();
        }
        usuario.setGoogleSub(sub);
        if (!usuario.isEmailVerificado()) {
            usuario.setEmailVerificado(true);
            usuario.setEmailVerificadoEn(Instant.now());
        }
        usuarioRepository.save(usuario);
        log.info("Usuario {} vinculó su cuenta de Google", usuario.getId());
    }

    private Usuario registrar(IdentidadGoogle identidad, GoogleLoginRequest request) {
        String documento = request.documentoIdentidad() == null ? null : request.documentoIdentidad().trim();
        if (!Boolean.TRUE.equals(request.aceptaPoliticaDatos()) || !StringUtils.hasText(documento)) {
            throw new GoogleRegistroIncompletoException(identidad.email(), identidad.nombre());
        }
        if (usuarioRepository.existsByDocumentoIdentidad(documento)) {
            throw new DocumentoYaRegistradoException();
        }
        Usuario usuario = new Usuario(identidad.email(), null, documento, nombre(identidad, request));
        usuario.setGoogleSub(identidad.sub());
        usuario.setEmailVerificado(true);
        usuario.setEmailVerificadoEn(Instant.now());
        try {
            usuario = usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException ex) {
            throw ConflictosUnicidad.traducir(ex);
        }
        // Ley 1581: misma evidencia de aceptación que /auth/register.
        sesionService.registrarConsentimiento(usuario.getId());
        return usuario;
    }

    /** El nombre que escribió la persona; si no, el de Google; si tampoco, la parte local del correo. */
    private static String nombre(IdentidadGoogle identidad, GoogleLoginRequest request) {
        if (StringUtils.hasText(request.nombre())) {
            return request.nombre().trim();
        }
        if (identidad.nombre() != null) {
            return identidad.nombre();
        }
        return identidad.email().split("@", 2)[0];
    }
}
