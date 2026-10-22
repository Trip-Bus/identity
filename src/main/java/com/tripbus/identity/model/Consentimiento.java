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

    /** El propio usuario aceptó la política al registrarse (US-001). */
    public static final String ORIGEN_USUARIO = "USUARIO";
    /** Un ADMIN creó la cuenta (US-103) y aceptó la política en nombre del usuario. */
    public static final String ORIGEN_ADMIN = "ADMIN_EN_NOMBRE_DEL_USUARIO";

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

    @Column(nullable = false, length = 40)
    private String origen;

    /** Administrador que aceptó en nombre del usuario; null si lo aceptó el propio usuario. */
    @Column(name = "otorgado_por")
    private UUID otorgadoPor;

    protected Consentimiento() {
    }

    public Consentimiento(UUID usuarioId, String politica, String version, Instant aceptadoEn) {
        this(usuarioId, politica, version, aceptadoEn, ORIGEN_USUARIO, null);
    }

    public Consentimiento(UUID usuarioId, String politica, String version, Instant aceptadoEn,
                          String origen, UUID otorgadoPor) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.politica = politica;
        this.version = version;
        this.aceptadoEn = aceptadoEn;
        this.origen = origen;
        this.otorgadoPor = otorgadoPor;
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

    public String getOrigen() {
        return origen;
    }

    public UUID getOtorgadoPor() {
        return otorgadoPor;
    }
}
