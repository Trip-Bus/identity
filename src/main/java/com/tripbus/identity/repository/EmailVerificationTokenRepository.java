package com.tripbus.identity.repository;

import com.tripbus.identity.model.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Reenvíos pedidos por el usuario desde {@code desde} (límite de POST /auth/verify-email/resend). */
    long countByUsuarioIdAndReenvioTrueAndCreatedAtAfter(UUID usuarioId, Instant desde);
}
