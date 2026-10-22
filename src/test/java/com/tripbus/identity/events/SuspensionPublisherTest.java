package com.tripbus.identity.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SuspensionPublisherTest {

    private final UUID userId = UUID.randomUUID();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;
    private IdentityEventPublisher events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        events = mock(IdentityEventPublisher.class);
        when(redis.opsForValue()).thenReturn(valores);
    }

    @Test
    void suspenderGuardaLaClaveSinTtlYPublicaUserSuspended() {
        new SuspensionPublisher(redis, events, true).publicarSuspension(userId, "Fraude", true);

        verify(valores).set("tripbus:suspended:" + userId, "1");
        verify(events).publish(IdentityEventPublisher.USER_SUSPENDED, Map.of("userId", userId, "motivo", "Fraude"));
    }

    @Test
    void suspenderUnaCuentaYaSuspendidaReescribeLaClavePeroNoRepiteElEvento() {
        new SuspensionPublisher(redis, events, true).publicarSuspension(userId, "Fraude", false);

        verify(valores).set("tripbus:suspended:" + userId, "1");
        verifyNoInteractions(events);
    }

    @Test
    void siRedisFallaAlSuspenderSeRegistraUnWarnYElEventoSeIntentaIgual() {
        doThrow(new RedisConnectionFailureException("caído")).when(valores).set(anyString(), anyString());

        new SuspensionPublisher(redis, events, true).publicarSuspension(userId, null, true);

        verify(events).publish(eq(IdentityEventPublisher.USER_SUSPENDED), any());
    }

    @Test
    void reactivarBorraLaClaveYPublicaUserReactivated() {
        new SuspensionPublisher(redis, events, true).publicarReactivacion(userId, "Aclarado", true);

        verify(redis).delete("tripbus:suspended:" + userId);
        verify(events).publish(IdentityEventPublisher.USER_REACTIVATED,
                Map.of("userId", userId, "motivo", "Aclarado"));
    }

    @Test
    void reactivarUnaCuentaYaActivaBorraLaClavePeroNoPublica() {
        new SuspensionPublisher(redis, events, true).publicarReactivacion(userId, null, false);

        verify(redis).delete("tripbus:suspended:" + userId);
        verify(events, never()).publish(anyString(), any());
    }

    @Test
    void siRedisFallaAlReactivarSeRegistraUnWarnYElEventoSeIntentaIgual() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("caído"));

        new SuspensionPublisher(redis, events, true).publicarReactivacion(userId, null, true);

        verify(events).publish(eq(IdentityEventPublisher.USER_REACTIVATED), any());
    }

    @Test
    void conLosEventosApagadosNoTocaRedisNiPublica() {
        SuspensionPublisher apagado = new SuspensionPublisher(redis, events, false);

        apagado.publicarSuspension(userId, "x", true);
        apagado.publicarReactivacion(userId, "x", true);

        verifyNoInteractions(redis, events);
    }
}
