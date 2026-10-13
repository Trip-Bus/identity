package com.tripbus.identity.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-006: avisa al Realtime Gateway de los tokens revocados (logout). Deja la clave
 * {@code tripbus:revoked:{jti}} = "1" con TTL igual a lo que le quedaba al token (mínimo 1 s)
 * y publica SessionRevoked en el stream de eventos. Es "best effort": la tabla
 * sesiones_revocadas es la fuente de verdad, así que si Redis falla solo se registra un WARN.
 * Usa el mismo interruptor que los eventos (tripbus.events.enabled): ambos son la integración
 * con Redis y se apagan juntos (p. ej. en pruebas sin Redis).
 */
@Component
public class RevocacionTokenPublisher {

    public static final String PREFIJO_CLAVE = "tripbus:revoked:";

    private static final Duration TTL_MINIMO = Duration.ofSeconds(1);
    private static final Logger log = LoggerFactory.getLogger(RevocacionTokenPublisher.class);

    private final StringRedisTemplate redis;
    private final IdentityEventPublisher events;
    private final boolean enabled;

    public RevocacionTokenPublisher(
            StringRedisTemplate redis,
            IdentityEventPublisher events,
            @Value("${tripbus.events.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.events = events;
        this.enabled = enabled;
    }

    public void publicarRevocacion(String jti, UUID userId, Instant expiraToken) {
        if (!enabled) {
            return;
        }
        Duration ttl = ttlRestante(expiraToken, Instant.now());
        try {
            redis.opsForValue().set(PREFIJO_CLAVE + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("No se pudo guardar la revocación del token {} en Redis: {}", jti, e.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jti", jti);
        payload.put("userId", userId);
        events.publish(IdentityEventPublisher.SESSION_REVOKED, payload);
    }

    /** Tiempo que le quedaba al token, redondeado hacia arriba al segundo y nunca menor a 1 s. */
    static Duration ttlRestante(Instant expiraToken, Instant ahora) {
        Duration restante = Duration.between(ahora, expiraToken);
        long segundos = (restante.toMillis() + 999) / 1000;
        Duration ttl = Duration.ofSeconds(segundos);
        return ttl.compareTo(TTL_MINIMO) < 0 ? TTL_MINIMO : ttl;
    }
}
