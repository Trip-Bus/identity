package com.tripbus.identity.service;

import com.tripbus.identity.events.RevocacionTokenPublisher;
import com.tripbus.identity.model.Consentimiento;
import com.tripbus.identity.model.SesionRevocada;
import com.tripbus.identity.repository.ConsentimientoRepository;
import com.tripbus.identity.repository.SesionRevocadaRepository;
import com.tripbus.identity.security.JwtKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Cierre de sesión (US-096) y evidencia del consentimiento de datos (US-001). */
@Service
public class SesionService {

    public static final String POLITICA_DATOS = "POLITICA_TRATAMIENTO_DATOS";
    public static final String VERSION_POLITICA = "1.0";

    private static final Logger log = LoggerFactory.getLogger(SesionService.class);

    private final SesionRevocadaRepository sesionRevocadaRepository;
    private final ConsentimientoRepository consentimientoRepository;
    private final JwtKeyProvider keyProvider;
    private final RevocacionTokenPublisher revocacionPublisher;
    private final RefreshTokenService refreshTokens;

    @Autowired
    public SesionService(SesionRevocadaRepository sesionRevocadaRepository,
                         ConsentimientoRepository consentimientoRepository,
                         JwtKeyProvider keyProvider,
                         RevocacionTokenPublisher revocacionPublisher, RefreshTokenService refreshTokens) {
        this.sesionRevocadaRepository = sesionRevocadaRepository;
        this.consentimientoRepository = consentimientoRepository;
        this.keyProvider = keyProvider;
        this.revocacionPublisher = revocacionPublisher;
        this.refreshTokens = refreshTokens;
    }

    public SesionService(SesionRevocadaRepository sesionRevocadaRepository,
                         ConsentimientoRepository consentimientoRepository, JwtKeyProvider keyProvider,
                         RevocacionTokenPublisher revocacionPublisher) {
        this(sesionRevocadaRepository, consentimientoRepository, keyProvider, revocacionPublisher, null);
    }

    /** Revoca el token actual: deja de ser aceptado aunque aún no haya expirado. */
    @Transactional
    public void cerrarSesion(String token) {
        cerrarSesion(token, null);
    }

    @Transactional
    public void cerrarSesion(String token, String refreshToken) {
        Claims claims = Jwts.parser().verifyWith(keyProvider.getPublicKey()).build()
                .parseSignedClaims(token).getPayload();
        UUID userId = UUID.fromString(claims.getSubject());
        if (refreshTokens != null) {
            Instant issuedAt = claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : Instant.EPOCH;
            refreshTokens.revokeForLogout(userId, issuedAt, refreshToken);
        }
        String jti = claims.getId();
        if (jti == null || sesionRevocadaRepository.existsById(jti)) {
            return;
        }
        Instant expira = claims.getExpiration() != null ? claims.getExpiration().toInstant() : Instant.now();
        sesionRevocadaRepository.save(new SesionRevocada(jti, userId, Instant.now(), expira));
        // US-006: el Realtime Gateway se entera por Redis (clave con TTL + evento SessionRevoked).
        revocacionPublisher.publicarRevocacion(jti, userId, expira);
    }

    @Transactional
    public void registrarConsentimiento(UUID usuarioId) {
        consentimientoRepository.save(new Consentimiento(usuarioId, POLITICA_DATOS, VERSION_POLITICA, Instant.now()));
    }

    /** US-103: el ADMIN que crea la cuenta acepta la política en nombre del usuario. */
    @Transactional
    public void registrarConsentimientoPorAdmin(UUID usuarioId, UUID adminId) {
        consentimientoRepository.save(new Consentimiento(usuarioId, POLITICA_DATOS, VERSION_POLITICA, Instant.now(),
                Consentimiento.ORIGEN_ADMIN, adminId));
    }

    /** Las revocaciones de tokens ya expirados no sirven: se purgan cada hora. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    @Transactional
    public void purgarRevocacionesExpiradas() {
        int borradas = sesionRevocadaRepository.deleteExpiradas(Instant.now());
        if (borradas > 0) {
            log.info("Revocaciones expiradas eliminadas: {}", borradas);
        }
    }
}
