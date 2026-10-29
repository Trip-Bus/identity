package com.tripbus.identity.service;

import com.tripbus.identity.client.CatalogClient;
import com.tripbus.identity.dto.CambiarEstadoRequest;
import com.tripbus.identity.dto.CambiarRolRequest;
import com.tripbus.identity.dto.CrearUsuarioRequest;
import com.tripbus.identity.dto.PageResponse;
import com.tripbus.identity.dto.UsuarioInternoResponse;
import com.tripbus.identity.dto.UsuarioResponse;
import com.tripbus.identity.events.IdentityEventPublisher;
import com.tripbus.identity.events.SuspensionPublisher;
import com.tripbus.identity.exception.CompanyIdRequeridoException;
import com.tripbus.identity.exception.DocumentoYaRegistradoException;
import com.tripbus.identity.exception.EmailYaRegistradoException;
import com.tripbus.identity.exception.NoPuedeSuspenderseException;
import com.tripbus.identity.exception.RolNoPermitidoException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.UsuarioSpecs;
import com.tripbus.identity.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UsuarioService {

    static final int TAMANO_PAGINA_POR_DEFECTO = 20;
    static final int TAMANO_PAGINA_MAXIMO = 100;

    private static final Sort ORDEN_LISTADO = Sort.by(Sort.Order.desc("fechaCreacion"), Sort.Order.desc("id"));
    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    private final UsuarioRepository usuarioRepository;
    private final CatalogClient catalogClient;
    private final PasswordHasher passwordHasher;
    private final PasswordResetService passwordResetService;
    private final SesionService sesionService;
    private final IdentityEventPublisher events;
    private final SuspensionPublisher suspensionPublisher;
    private final SecureRandom secureRandom = new SecureRandom();
    private final RefreshTokenService refreshTokens;
    private final LoginAttemptService loginAttempts;

    @Autowired
    public UsuarioService(UsuarioRepository usuarioRepository,
                          CatalogClient catalogClient,
                          PasswordHasher passwordHasher,
                          PasswordResetService passwordResetService,
                          SesionService sesionService,
                          IdentityEventPublisher events,
                          SuspensionPublisher suspensionPublisher,
                          RefreshTokenService refreshTokens,
                          LoginAttemptService loginAttempts) {
        this.usuarioRepository = usuarioRepository;
        this.catalogClient = catalogClient;
        this.passwordHasher = passwordHasher;
        this.passwordResetService = passwordResetService;
        this.sesionService = sesionService;
        this.events = events;
        this.suspensionPublisher = suspensionPublisher;
        this.refreshTokens = refreshTokens;
        this.loginAttempts = loginAttempts;
    }

    public UsuarioService(UsuarioRepository usuarioRepository, CatalogClient catalogClient, PasswordHasher passwordHasher,
            PasswordResetService passwordResetService, SesionService sesionService, IdentityEventPublisher events,
            SuspensionPublisher suspensionPublisher) {
        this(usuarioRepository, catalogClient, passwordHasher, passwordResetService, sesionService, events,
                suspensionPublisher, null, null);
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtenerPorId(UUID id) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);
        return toResponse(usuario);
    }

    /** Consulta servicio a servicio (/internal/users/{id}): sin email, documento ni passwordHash. */
    @Transactional(readOnly = true)
    public UsuarioInternoResponse obtenerInterno(UUID id) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);
        return new UsuarioInternoResponse(usuario.getId(), usuario.getNombre(), usuario.getRol(),
                usuario.getCompanyId(), usuario.isActivo());
    }

    /** US-102: listado paginado, del más reciente al más antiguo. size se limita a 1..100. */
    @Transactional(readOnly = true)
    public PageResponse<UsuarioResponse> listar(RolUsuario rol, UUID companyId, Boolean activo, String q,
                                                int page, int size) {
        int tamano = size < 1 ? TAMANO_PAGINA_POR_DEFECTO : Math.min(size, TAMANO_PAGINA_MAXIMO);
        PageRequest pagina = PageRequest.of(Math.max(page, 0), tamano, ORDEN_LISTADO);
        Page<Usuario> resultado = usuarioRepository.findAll(UsuarioSpecs.filtrar(rol, companyId, activo, q), pagina);
        return new PageResponse<>(resultado.getContent().stream().map(UsuarioService::toResponse).toList(),
                resultado.getNumber(), resultado.getSize(), resultado.getTotalElements(), resultado.getTotalPages());
    }

    @Transactional
    public UsuarioResponse cambiarRol(UUID id, CambiarRolRequest request) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);

        RolUsuario nuevoRol = request.rol();
        boolean requiereCompanyId = esRolDeEmpresa(nuevoRol);
        if (requiereCompanyId) {
            // US-101: sin una empresa existente y ACTIVA en catalog no se asigna el rol.
            validarEmpresa(request.companyId());
        }

        usuario.setRol(nuevoRol);
        usuario.setCompanyId(requiereCompanyId ? request.companyId() : null);
        usuario = usuarioRepository.save(usuario);

        return toResponse(usuario);
    }

    /**
     * US-103: un ADMIN crea un OPERADOR o CONDUCTOR. No se define contraseña: se guarda el hash de un
     * secreto aleatorio que nadie conoce y se envía el enlace de recuperación para que la persona
     * defina la suya. El consentimiento de datos queda aceptado por el ADMIN en nombre del usuario.
     */
    @Transactional
    public UsuarioResponse crear(CrearUsuarioRequest request, UUID adminId) {
        if (!esRolDeEmpresa(request.rol())) {
            throw new RolNoPermitidoException();
        }
        if (usuarioRepository.existsByEmail(request.email())) {
            throw new EmailYaRegistradoException();
        }
        if (usuarioRepository.existsByDocumentoIdentidad(request.documentoIdentidad())) {
            throw new DocumentoYaRegistradoException();
        }
        validarEmpresa(request.companyId());

        Usuario usuario = new Usuario(request.email(), passwordHasher.hash(secretoAleatorio()),
                request.documentoIdentidad(), request.nombre());
        usuario.setRol(request.rol());
        usuario.setCompanyId(request.companyId());
        try {
            usuario = usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException ex) {
            throw ConflictosUnicidad.traducir(ex);
        }

        sesionService.registrarConsentimientoPorAdmin(usuario.getId(), adminId);
        passwordResetService.enviarEnlaceDefinirContrasena(usuario);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", usuario.getId());
        payload.put("email", usuario.getEmail());
        payload.put("role", usuario.getRol());
        payload.put("timestamp", Instant.now());
        events.publish(IdentityEventPublisher.USER_REGISTERED, payload);

        log.info("Usuario {} ({}) creado por el administrador {}", usuario.getId(), usuario.getRol(), adminId);
        return toResponse(usuario);
    }

    /**
     * US-104: suspende o reactiva una cuenta. Los tokens de una cuenta suspendida dejan de valer de
     * inmediato porque JwtAuthenticationFilter (y /auth/validate para el API Gateway) revisa
     * usuarios.activo en cada petición; además se avisa por Redis al Realtime Gateway.
     */
    @Transactional
    public UsuarioResponse cambiarEstado(UUID id, CambiarEstadoRequest request, UUID adminId) {
        boolean activo = request.activo();
        if (!activo && id.equals(adminId)) {
            throw new NoPuedeSuspenderseException();
        }
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);
        boolean cambio = usuario.isActivo() != activo;
        if (cambio) {
            usuario.setActivo(activo);
            usuario = usuarioRepository.save(usuario);
            if (!activo && refreshTokens != null) refreshTokens.revokeAll(id);
        }

        if (activo) {
            suspensionPublisher.publicarReactivacion(id, request.motivo(), cambio);
        } else {
            suspensionPublisher.publicarSuspension(id, request.motivo(), cambio);
        }
        log.info("Usuario {} {} por el administrador {}", id, activo ? "reactivado" : "suspendido", adminId);
        return toResponse(usuario);
    }

    @Transactional
    public void desbloquear(UUID id) {
        Usuario usuario = usuarioRepository.findById(id).orElseThrow(UsuarioNoEncontradoException::new);
        if (loginAttempts != null) loginAttempts.clear(usuario.getEmail());
    }

    private void validarEmpresa(UUID companyId) {
        if (companyId == null) {
            throw new CompanyIdRequeridoException();
        }
        catalogClient.validarEmpresaActiva(companyId);
    }

    private static boolean esRolDeEmpresa(RolUsuario rol) {
        return rol == RolUsuario.OPERADOR || rol == RolUsuario.CONDUCTOR;
    }

    private String secretoAleatorio() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static UsuarioResponse toResponse(Usuario usuario) {
        return new UsuarioResponse(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.getRol(),
                usuario.getCompanyId(),
                usuario.isActivo(),
                usuario.getFechaCreacion());
    }
}
