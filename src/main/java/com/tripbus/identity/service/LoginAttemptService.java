package com.tripbus.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Contadores y bloqueos de login. Redis solo recibe hashes de correos normalizados. */
@Service
public class LoginAttemptService {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String PREFIX = "tripbus:identity:login:";
    private static final RedisScript<Long> INCREMENT_WITH_WINDOW = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",
            Long.class);
    private final StringRedisTemplate redis;
    private final int maxAttempts;
    private final Duration window;
    private final Duration lockout;

    public LoginAttemptService(StringRedisTemplate redis,
            @Value("${tripbus.identity.login.max-attempts:5}") int maxAttempts,
            @Value("${tripbus.identity.login.window:15m}") Duration window,
            @Value("${tripbus.identity.login.lockout:15m}") Duration lockout) {
        this.redis = redis;
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.lockout = lockout;
    }

    public long remainingLockSeconds(String email) {
        try {
            Long ttl = redis.getExpire(lockKey(email));
            return ttl != null && ttl > 0 ? ttl : 0;
        } catch (Exception e) {
            log.warn("Redis no disponible al consultar bloqueo de login: {}", e.getMessage());
            return 0;
        }
    }

    /** Returns true exactly for the attempt that reaches the configured threshold. */
    public boolean failed(String email) {
        Long count;
        String key = countKey(email);
        try {
            count = redis.execute(INCREMENT_WITH_WINDOW, List.of(key), String.valueOf(window.toSeconds()));
        } catch (Exception e) {
            log.warn("Redis no disponible; se omite el bloqueo de login: {}", e.getMessage());
            return false;
        }
        if (count != null && count >= maxAttempts) {
            try {
                redis.opsForValue().set(lockKey(email), "1", lockout);
            } catch (Exception e) {
                log.warn("Redis no disponible al establecer el bloqueo de login: {}", e.getMessage());
                return false;
            }
            try {
                redis.delete(key);
            } catch (Exception e) {
                log.warn("Redis no disponible al limpiar el contador bloqueado: {}", e.getMessage());
            }
            return true;
        }
        return false;
    }

    public void succeeded(String email) { clear(email); }
    public void clear(String email) {
        try { redis.delete(countKey(email)); redis.delete(lockKey(email)); }
        catch (Exception e) { log.warn("Redis no disponible al limpiar intentos de login: {}", e.getMessage()); }
    }

    private static String countKey(String email) { return PREFIX + "count:" + hash(email); }
    private static String lockKey(String email) { return PREFIX + "lock:" + hash(email); }
    private static String hash(String email) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 no está disponible", e); }
    }
}
