package com.tripbus.identity.service;

import com.tripbus.identity.dto.CodigosRecuperacionResponse;
import com.tripbus.identity.dto.ConfigurarMfaResponse;
import com.tripbus.identity.exception.ContrasenaActualIncorrectaException;
import com.tripbus.identity.exception.MfaException;
import com.tripbus.identity.exception.UsuarioNoEncontradoException;
import com.tripbus.identity.model.CodigoRecuperacion;
import com.tripbus.identity.model.DesafioMfa;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.model.VerificacionDosPasos;
import com.tripbus.identity.repository.CodigoRecuperacionRepository;
import com.tripbus.identity.repository.DesafioMfaRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.repository.VerificacionDosPasosRepository;
import com.tripbus.identity.security.Base32;
import com.tripbus.identity.security.CifradorSecretos;
import com.tripbus.identity.security.GeneradorTokens;
import com.tripbus.identity.security.PasswordHasher;
import com.tripbus.identity.security.Totp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * US-106: verificación en dos pasos con TOTP (RFC 6238) para ADMIN y OPERADOR.
 *
 * <ul>
 *   <li>setup genera el secreto (cifrado con AES-GCM) y la URI otpauth:// para el QR; enable lo activa con
 *   un código y entrega 10 códigos de recuperación (solo se guardan sus hashes).</li>
 *   <li>Con la 2FA activa el login no entrega tokens: entrega un mfaToken (un uso, 5 min) que se canjea en
 *   /auth/2fa con el código de la app o un código de recuperación. 5 fallos invalidan el mfaToken.</li>
 *   <li>Se acepta el paso actual ±1 y nunca un paso igual o anterior al último usado.</li>
 * </ul>
 */
@Service
public class MfaService {

    public static final String EMISOR = "TripBus";
    static final int CODIGOS_RECUPERACION = 10;
    static final Duration VIGENCIA_DESAFIO = Duration.ofMinutes(5);
    static final int MAXIMO_FALLOS = 5;
    /** Sin letras ni números que se confundan (O/0, I/1): XXXXX-XXXXX, unos 50 bits por código. */
    static final String ALFABETO_RECUPERACION = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LARGO_RECUPERACION = 10;

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final VerificacionDosPasosRepository verificaciones;
    private final CodigoRecuperacionRepository codigos;
    private final DesafioMfaRepository desafios;
    private final UsuarioRepository usuarios;
    private final PasswordHasher passwordHasher;
    private final GeneradorTokens tokens;
    private final CifradorSecretos cifrador;
    private final SecureRandom random = new SecureRandom();

    public MfaService(VerificacionDosPasosRepository verificaciones, CodigoRecuperacionRepository codigos,
                      DesafioMfaRepository desafios, UsuarioRepository usuarios, PasswordHasher passwordHasher,
                      GeneradorTokens tokens, CifradorSecretos cifrador) {
        this.verificaciones = verificaciones;
        this.codigos = codigos;
        this.desafios = desafios;
        this.usuarios = usuarios;
        this.passwordHasher = passwordHasher;
        this.tokens = tokens;
        this.cifrador = cifrador;
    }

    /** Genera (o reemplaza, si no se había activado) el secreto. 409 si la 2FA ya está activa. */
    @Transactional
    public ConfigurarMfaResponse configurar(UUID usuarioId) {
        Usuario usuario = usuarios.findById(usuarioId).orElseThrow(UsuarioNoEncontradoException::new);
        VerificacionDosPasos verificacion = verificaciones.findLockedByUsuarioId(usuarioId).orElse(null);
        if (verificacion != null && verificacion.isActiva()) {
            throw MfaException.yaActiva();
        }
        byte[] secreto = Totp.nuevoSecreto();
        String cifrado = cifrador.cifrar(secreto, usuarioId.toString());
        Instant ahora = Instant.now();
        if (verificacion == null) {
            verificacion = new VerificacionDosPasos(usuarioId, cifrado, ahora);
        } else {
            verificacion.setSecretoCifrado(cifrado);
            verificacion.setCreadoEn(ahora);
            verificacion.setUltimoPaso(null);
        }
        verificaciones.save(verificacion);
        String base32 = Base32.codificar(secreto);
        return new ConfigurarMfaResponse(base32, Totp.uri(EMISOR, usuario.getEmail(), base32));
    }

    /** Activa la 2FA con el primer código de la app y entrega los códigos de recuperación (una sola vez). */
    @Transactional
    public CodigosRecuperacionResponse activar(UUID usuarioId, String codigo) {
        VerificacionDosPasos verificacion = verificaciones.findLockedByUsuarioId(usuarioId)
                .orElseThrow(MfaException::sinConfigurar);
        if (verificacion.isActiva()) {
            throw MfaException.yaActiva();
        }
        long paso = verificarCodigo(verificacion, codigo).orElseThrow(MfaException::codigoInvalido);
        Instant ahora = Instant.now();
        verificacion.setActiva(true);
        verificacion.setActivadaEn(ahora);
        verificacion.setUltimoPaso(paso);
        codigos.borrarDeUsuario(usuarioId);
        List<String> planos = new ArrayList<>(CODIGOS_RECUPERACION);
        for (int i = 0; i < CODIGOS_RECUPERACION; i++) {
            String plano = nuevoCodigoRecuperacion();
            planos.add(plano);
            codigos.save(new CodigoRecuperacion(usuarioId, tokens.hash(normalizar(plano))));
        }
        log.info("Usuario {} activó la verificación en dos pasos", usuarioId);
        return new CodigosRecuperacionResponse(planos);
    }

    /** Desactiva la 2FA: exige la contraseña y un código vigente de la app. */
    @Transactional
    public void desactivar(UUID usuarioId, String password, String codigo) {
        VerificacionDosPasos verificacion = verificaciones.findLockedByUsuarioId(usuarioId)
                .filter(VerificacionDosPasos::isActiva)
                .orElseThrow(MfaException::noActiva);
        Usuario usuario = usuarios.findById(usuarioId).orElseThrow(UsuarioNoEncontradoException::new);
        if (!passwordHasher.matches(password, usuario.getPasswordHash())) {
            throw new ContrasenaActualIncorrectaException();
        }
        if (verificarCodigo(verificacion, codigo).isEmpty()) {
            throw MfaException.codigoInvalido();
        }
        eliminarDatos(usuarioId);
        log.info("Usuario {} desactivó la verificación en dos pasos", usuarioId);
    }

    @Transactional(readOnly = true)
    public boolean activa(UUID usuarioId) {
        return verificaciones.existsByUsuarioIdAndActivaTrue(usuarioId);
    }

    /** Login con 2FA: crea el desafío y devuelve el mfaToken en claro (en la BD queda su hash). */
    @Transactional
    public String emitirDesafio(UUID usuarioId) {
        String plano = tokens.generar();
        desafios.save(new DesafioMfa(tokens.hash(plano), usuarioId, Instant.now().plus(VIGENCIA_DESAFIO)));
        return plano;
    }

    /**
     * Canjea el mfaToken con el código de la app o uno de recuperación. Los fallos se guardan aunque la
     * petición falle (noRollbackFor): al quinto el mfaToken deja de servir.
     *
     * @return el id del usuario que completó el segundo paso
     */
    @Transactional(noRollbackFor = MfaException.class)
    public UUID verificarDesafio(String mfaToken, String codigo, String codigoRecuperacion) {
        Instant ahora = Instant.now();
        DesafioMfa desafio = desafios.findLockedByTokenHash(tokens.hash(mfaToken))
                .filter(d -> vigente(d, ahora))
                .orElseThrow(MfaException::desafioInvalido);
        UUID usuarioId = desafio.getUsuarioId();
        boolean valido = StringUtils.hasText(codigo)
                ? usarCodigoDeApp(usuarioId, codigo.trim())
                : usarCodigoDeRecuperacion(usuarioId, codigoRecuperacion, ahora);
        if (!valido) {
            int intentos = desafio.getIntentos() + 1;
            desafio.setIntentos(intentos);
            if (intentos >= MAXIMO_FALLOS) {
                desafio.setUsadoEn(ahora);
            }
            throw MfaException.codigoInvalidoEnLogin();
        }
        desafio.setUsadoEn(ahora);
        return usuarioId;
    }

    /** Borra secreto, códigos y desafíos (desactivar la 2FA o eliminar la cuenta, US-131). */
    @Transactional
    public void eliminarDatos(UUID usuarioId) {
        codigos.borrarDeUsuario(usuarioId);
        desafios.borrarDeUsuario(usuarioId);
        verificaciones.findById(usuarioId).ifPresent(verificaciones::delete);
    }

    /** Los desafíos vencidos no sirven: se purgan cada hora. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @Transactional
    public void purgarDesafiosVencidos() {
        int borrados = desafios.borrarVencidosAntesDe(Instant.now());
        if (borrados > 0) {
            log.info("Desafíos de 2FA vencidos eliminados: {}", borrados);
        }
    }

    private static boolean vigente(DesafioMfa desafio, Instant ahora) {
        return desafio.getUsadoEn() == null && desafio.getExpiraEn().isAfter(ahora)
                && desafio.getIntentos() < MAXIMO_FALLOS;
    }

    private boolean usarCodigoDeApp(UUID usuarioId, String codigo) {
        VerificacionDosPasos verificacion = verificaciones.findLockedByUsuarioId(usuarioId)
                .filter(VerificacionDosPasos::isActiva).orElse(null);
        if (verificacion == null) {
            return false;
        }
        OptionalLong paso = verificarCodigo(verificacion, codigo);
        paso.ifPresent(verificacion::setUltimoPaso);
        return paso.isPresent();
    }

    private boolean usarCodigoDeRecuperacion(UUID usuarioId, String codigo, Instant ahora) {
        if (!StringUtils.hasText(codigo)) {
            return false;
        }
        return codigos.findFirstByUsuarioIdAndCodigoHashAndUsadoEnIsNull(usuarioId, tokens.hash(normalizar(codigo)))
                .map(c -> {
                    c.setUsadoEn(ahora);
                    return true;
                })
                .orElse(false);
    }

    private OptionalLong verificarCodigo(VerificacionDosPasos verificacion, String codigo) {
        byte[] secreto = cifrador.descifrar(verificacion.getSecretoCifrado(), verificacion.getUsuarioId().toString());
        return Totp.verificar(secreto, codigo, Instant.now(), verificacion.getUltimoPaso());
    }

    private String nuevoCodigoRecuperacion() {
        StringBuilder codigo = new StringBuilder(LARGO_RECUPERACION + 1);
        for (int i = 0; i < LARGO_RECUPERACION; i++) {
            if (i == LARGO_RECUPERACION / 2) {
                codigo.append('-');
            }
            codigo.append(ALFABETO_RECUPERACION.charAt(random.nextInt(ALFABETO_RECUPERACION.length())));
        }
        return codigo.toString();
    }

    /** Sin guiones ni espacios y en mayúsculas: "abcde fghjk" y "ABCDE-FGHJK" son el mismo código. */
    static String normalizar(String codigo) {
        return codigo.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
