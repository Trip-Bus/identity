package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-106: código de recuperación de la 2FA. Solo se guarda su hash SHA-256; sirve una sola vez. */
@Entity
@Table(name = "mfa_codigos_recuperacion")
public class CodigoRecuperacion {

    @Id
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "codigo_hash", nullable = false, length = 64)
    private String codigoHash;

    @Column(name = "usado_en")
    private Instant usadoEn;

    protected CodigoRecuperacion() {
    }

    public CodigoRecuperacion(UUID usuarioId, String codigoHash) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.codigoHash = codigoHash;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getCodigoHash() {
        return codigoHash;
    }

    public Instant getUsadoEn() {
        return usadoEn;
    }

    public void setUsadoEn(Instant usadoEn) {
        this.usadoEn = usadoEn;
    }
}
