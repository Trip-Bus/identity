package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sesiones_revocadas")
public class SesionRevocada {

    @Id
    private String jti;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "revocada_en", nullable = false)
    private Instant revocadaEn;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    protected SesionRevocada() {
    }

    public SesionRevocada(String jti, UUID usuarioId, Instant revocadaEn, Instant expiraEn) {
        this.jti = jti;
        this.usuarioId = usuarioId;
        this.revocadaEn = revocadaEn;
        this.expiraEn = expiraEn;
    }

    public String getJti() {
        return jti;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }
}
