package com.tripbus.identity.service;

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

    public SesionService(SesionRevocadaRepository sesionRevocadaRepository,
                         ConsentimientoRepository consentimientoRepository,
                         JwtKeyProvider keyProvider) {
        this.sesionRevocadaRepository = sesionRevocadaRepository;
        this.consentimientoRepository = consentimientoRepository;
        this.keyProvider = keyProvider;
    }

    /** Revoca el token actual: deja de ser aceptado aunque aún no haya expirado. */
    @Transactional
    public void cerrarSesion(String token) {
        Claims claims = Jwts.parser().verifyWith(keyProvider.getPublicKey()).build()
                .parseSignedClaims(token).getPayload();
        String jti = claims.getId();
        if (jti == null || sesionRevocadaRepository.existsById(jti)) {
            return;
        }
        Instant expira = claims.getExpiration() != null ? claims.getExpiration().toInstant() : Instant.now();
        sesionRevocadaRepository.save(new SesionRevocada(jti, UUID.fromString(claims.getSubject()), Instant.now(), expira));
    }

    @Transactional
    public void registrarConsentimiento(UUID usuarioId) {
        consentimientoRepository.save(new Consentimiento(usuarioId, POLITICA_DATOS, VERSION_POLITICA, Instant.now()));
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
