package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id private UUID id;
    @Column(name="user_id", nullable=false) private UUID userId;
    @Column(name="token_hash", nullable=false, unique=true, length=64) private String tokenHash;
    @Column(name="familia_id", nullable=false) private UUID familyId;
    @Column(name="creado_en", nullable=false) private Instant createdAt;
    @Column(name="expira_en", nullable=false) private Instant expiresAt;
    @Column(name="usado_en") private Instant usedAt;
    @Column(name="revocado_en") private Instant revokedAt;
    protected RefreshToken() { }
    public RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt) {
        this.id=UUID.randomUUID(); this.userId=userId; this.tokenHash=tokenHash; this.familyId=familyId;
        this.createdAt=createdAt; this.expiresAt=expiresAt;
    }
    public UUID getUserId(){return userId;} public UUID getFamilyId(){return familyId;}
    public Instant getCreatedAt(){return createdAt;} public Instant getExpiresAt(){return expiresAt;}
    public Instant getUsedAt(){return usedAt;} public Instant getRevokedAt(){return revokedAt;}
    public void setUsedAt(Instant at){usedAt=at;} public void setRevokedAt(Instant at){revokedAt=at;}
}
