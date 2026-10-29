package com.tripbus.identity.repository;

import com.tripbus.identity.model.RefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findLockedByHash(@Param("hash") String hash);
    List<RefreshToken> findByUserIdAndCreatedAtLessThanEqualAndRevokedAtIsNull(UUID userId, Instant cutoff);
    @Modifying @Query("update RefreshToken t set t.revokedAt=:at where t.userId=:userId and t.revokedAt is null")
    int revokeAll(@Param("userId") UUID userId, @Param("at") Instant at);
    @Modifying @Query("update RefreshToken t set t.revokedAt=:at where t.familyId=:familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);
    @Modifying @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
