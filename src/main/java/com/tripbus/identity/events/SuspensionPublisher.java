package com.tripbus.identity.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * US-104: avisa a los demás servicios de una suspensión o reactivación de cuenta. Deja (o borra) la
 * clave {@code tripbus:suspended:{userId}} = "1" sin TTL, que usará el Realtime Gateway, y publica
 * UserSuspended / UserReactivated en el stream de eventos. Es "best effort" como los eventos: la
 * columna usuarios.activo es la fuente de verdad, así que si Redis falla solo se registra un WARN.
 * Usa el mismo interruptor que los eventos (tripbus.events.enabled).
 */
@Component
public class SuspensionPublisher {

    public static final String PREFIJO_CLAVE = "tripbus:suspended:";

    private static final Logger log = LoggerFactory.getLogger(SuspensionPublisher.class);

    private final StringRedisTemplate redis;
    private final IdentityEventPublisher events;
    private final boolean enabled;

    public SuspensionPublisher(
            StringRedisTemplate redis,
            IdentityEventPublisher events,
            @Value("${tripbus.events.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.events = events;
        this.enabled = enabled;
    }

    /**
     * @param cambio false si la cuenta ya estaba suspendida: se vuelve a escribir la clave (repara una
     *               falla anterior de Redis) pero no se repite el evento.
     */
    public void publicarSuspension(UUID userId, String motivo, boolean cambio) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIJO_CLAVE + userId, "1");
        } catch (Exception e) {
            log.warn("No se pudo guardar la suspensión del usuario {} en Redis: {}", userId, e.getMessage());
        }
        if (cambio) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("motivo", motivo);
            events.publish(IdentityEventPublisher.USER_SUSPENDED, payload);
        }
    }

    /** @param cambio false si la cuenta ya estaba activa: se borra la clave pero no se repite el evento. */
    public void publicarReactivacion(UUID userId, String motivo, boolean cambio) {
        if (!enabled) {
            return;
        }
        try {
            redis.delete(PREFIJO_CLAVE + userId);
        } catch (Exception e) {
            log.warn("No se pudo borrar la suspensión del usuario {} en Redis: {}", userId, e.getMessage());
        }
        if (cambio) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("motivo", motivo);
            events.publish(IdentityEventPublisher.USER_REACTIVATED, payload);
        }
    }
}
