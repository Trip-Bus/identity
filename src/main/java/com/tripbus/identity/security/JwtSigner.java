package com.tripbus.identity.security;

import com.tripbus.identity.model.RolUsuario;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtSigner {

    private final JwtKeyProvider keyProvider;
    private final Duration defaultTtl;

    public JwtSigner(
            JwtKeyProvider keyProvider,
            @Value("${security.jwt.expiration-minutes:60}") long defaultExpirationMinutes) {
        this.keyProvider = keyProvider;
        this.defaultTtl = Duration.ofMinutes(defaultExpirationMinutes);
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public String sign(UUID userId, RolUsuario rol, UUID companyId, Duration ttl) {
        Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
        Instant now = Instant.now();

        JwtBuilder builder = Jwts.builder()
                .header().keyId(keyProvider.getKeyId()).and()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("role", rol.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(effectiveTtl)));

        if (companyId != null) {
            builder.claim("companyId", companyId.toString());
        }

        return builder.signWith(keyProvider.getPrivateKey(), Jwts.SIG.RS256).compact();
    }
}
