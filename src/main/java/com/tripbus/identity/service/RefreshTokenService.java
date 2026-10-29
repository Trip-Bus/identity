package com.tripbus.identity.service;

import com.tripbus.identity.dto.AuthResponse;
import com.tripbus.identity.exception.RefreshTokenException;
import com.tripbus.identity.model.RefreshToken;
import com.tripbus.identity.model.Usuario;
import com.tripbus.identity.repository.RefreshTokenRepository;
import com.tripbus.identity.repository.UsuarioRepository;
import com.tripbus.identity.security.GeneradorTokens;
import com.tripbus.identity.security.JwtSigner;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private final RefreshTokenRepository repository;
    private final UsuarioRepository users;
    private final GeneradorTokens tokens;
    private final JwtSigner signer;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, UsuarioRepository users,
            GeneradorTokens tokens, JwtSigner signer,
            @Value("${tripbus.identity.refresh-ttl:14d}") Duration ttl) {
        this.repository=repository; this.users=users; this.tokens=tokens; this.signer=signer; this.ttl=ttl;
    }
    public Issued issue(UUID userId) { return issue(userId, UUID.randomUUID()); }
    private Issued issue(UUID userId, UUID family) {
        String plain=tokens.generar(); Instant now=Instant.now(); Instant createdAt=now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt=now.plus(ttl);
        repository.save(new RefreshToken(userId, tokens.hash(plain), family, createdAt, expiresAt));
        return new Issued(plain, expiresAt);
    }
    @Transactional(noRollbackFor = RefreshTokenException.class)
    public AuthResponse refresh(String plain) {
        RefreshToken row=repository.findLockedByHash(tokens.hash(plain)).orElseThrow(()->invalid());
        Instant now=Instant.now();
        if (row.getUsedAt()!=null) {
            repository.revokeFamily(row.getFamilyId(), now);
            throw new RefreshTokenException("REFRESH_TOKEN_REUSED");
        }
        if (row.getRevokedAt()!=null || !row.getExpiresAt().isAfter(now)) throw invalid();
        Usuario user=users.findById(row.getUserId()).orElseThrow(()->invalid());
        if (!user.isActivo()) throw new RefreshTokenException("ACCOUNT_SUSPENDED");
        row.setUsedAt(now); repository.save(row);
        Issued next=issue(user.getId(), row.getFamilyId());
        return accessResponse(user, next);
    }
    @Transactional
    public void revokeAll(UUID userId) { repository.revokeAll(userId, Instant.now()); }
    @Transactional
    public void revokeForLogout(UUID userId, Instant issuedAt, String plain) {
        Instant now=Instant.now();
        if (plain!=null && !plain.isBlank()) {
            repository.findLockedByHash(tokens.hash(plain)).ifPresent(row -> {
                if (row.getUserId().equals(userId)) repository.revokeFamily(row.getFamilyId(), now);
            });
            return;
        }
        // JWT iat tiene precisión de segundos; el margen incluye la sesión creada en ese mismo segundo.
        repository.findByUserIdAndCreatedAtLessThanEqualAndRevokedAtIsNull(userId, issuedAt)
                .forEach(row -> row.setRevokedAt(now));
    }
    @Scheduled(cron="0 0 3 * * *") @Transactional
    public void purgeExpired() {
        int removed=repository.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
        if (removed>0) log.info("Refresh tokens expirados eliminados: {}", removed);
    }
    public Duration ttl(){return ttl;}
    private AuthResponse accessResponse(Usuario user, Issued refresh) {
        Duration accessTtl=signer.getDefaultTtl(); Instant expires=Instant.now().plus(accessTtl);
        String access=signer.sign(user.getId(), user.getRol(), user.getCompanyId(), user.getEmail(),
                user.isEmailVerificado(), accessTtl);
        return new AuthResponse(access, expires, user.getId(), user.getRol(), refresh.token(), refresh.expiresAt());
    }
    private static RefreshTokenException invalid(){return new RefreshTokenException("INVALID_REFRESH_TOKEN");}
    public record Issued(String token, Instant expiresAt) { }
}
