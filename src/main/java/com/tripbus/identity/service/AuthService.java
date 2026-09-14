package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.dto.LoginRequest;
import com.tripbus.identity.dto.RegistroRequest;
import com.tripbus.identity.exception.CredencialesInvalidasException;
import com.tripbus.identity.exception.CuentaSuspendidaException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.JwtSigner;
import com.tripbus.identity.security.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordHasher passwordHasher;
    private final JwtSigner jwtSigner;

    public AuthService(UsuarioRepository usuarioRepository, PasswordHasher passwordHasher, JwtSigner jwtSigner) {
        this.usuarioRepository = usuarioRepository;
        this.passwordHasher = passwordHasher;
        this.jwtSigner = jwtSigner;
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
        usuario = usuarioRepository.save(usuario);

        return emitirRespuesta(usuario);
    }

    public AuthResponse login(LoginRequest request) {
        // Decisión de seguridad: usuario inexistente y contraseña incorrecta lanzan la
        // MISMA excepción con el MISMO mensaje, para no permitir que alguien deduzca,
        // por la respuesta, si un correo está registrado en el sistema.
        Usuario usuario = usuarioRepository.findByEmail(request.email())
                .orElseThrow(CredencialesInvalidasException::new);

        if (!passwordHasher.matches(request.password(), usuario.getPasswordHash())) {
            throw new CredencialesInvalidasException();
        }

        if (!usuario.isActivo()) {
            throw new CuentaSuspendidaException();
        }

        return emitirRespuesta(usuario);
    }

    private AuthResponse emitirRespuesta(Usuario usuario) {
        Duration ttl = jwtSigner.getDefaultTtl();
        Instant expiresAt = Instant.now().plus(ttl);
        String accessToken = jwtSigner.sign(usuario.getId(), usuario.getRol(), usuario.getCompanyId(), ttl);
        return new AuthResponse(accessToken, expiresAt, usuario.getId(), usuario.getRol());
    }
}
