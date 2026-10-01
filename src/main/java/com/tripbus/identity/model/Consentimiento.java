package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consentimientos")
public class Consentimiento {

    @Id
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(nullable = false)
    private String politica;

    @Column(nullable = false)
    private String version;

    @Column(name = "aceptado_en", nullable = false)
    private Instant aceptadoEn;

    protected Consentimiento() {
    }

    public Consentimiento(UUID usuarioId, String politica, String version, Instant aceptadoEn) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.politica = politica;
        this.version = version;
        this.aceptadoEn = aceptadoEn;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getPolitica() {
        return politica;
    }

    public String getVersion() {
        return version;
    }
}
