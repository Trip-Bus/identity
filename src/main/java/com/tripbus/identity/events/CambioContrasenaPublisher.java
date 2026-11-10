package com.tripbus.identity.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * US-129: deja la clave {@code tripbus:pwd-changed:{userId}} = epoch en segundos del cambio de contraseña,
 * con TTL igual a la vida máxima de un token (JWT_EXPIRATION_MINUTES, 60 min): pasado ese tiempo ya no
 * queda ningún token anterior vivo. El Realtime Gateway cierra con ella las conexiones abiertas con un
 * token emitido antes ({@code iat} menor). Es "best effort" como las demás claves: la columna
 * usuarios.password_changed_at es la fuente de verdad, así que si Redis falla solo se registra un WARN.
 * Usa el mismo interruptor que los eventos (tripbus.events.enabled).
 */
@Component
public class CambioContrasenaPublisher {

    public static final String PREFIJO_CLAVE = "tripbus:pwd-changed:";

    private static final Logger log = LoggerFactory.getLogger(CambioContrasenaPublisher.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final boolean enabled;

    public CambioContrasenaPublisher(
            StringRedisTemplate redis,
            @Value("${security.jwt.expiration-minutes:60}") long vidaMaximaTokenMinutos,
            @Value("${tripbus.events.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(vidaMaximaTokenMinutos);
        this.enabled = enabled;
    }

    public void publicarCambio(UUID userId, Instant passwordChangedAt) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIJO_CLAVE + userId, String.valueOf(passwordChangedAt.getEpochSecond()), ttl);
        } catch (Exception e) {
            log.warn("No se pudo guardar el cambio de contraseña del usuario {} en Redis: {}", userId, e.getMessage());
        }
    }
}
