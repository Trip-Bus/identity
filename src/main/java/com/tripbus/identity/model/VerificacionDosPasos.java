package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-106: secreto TOTP (cifrado) de un usuario. Inactiva entre /2fa/setup y /2fa/enable. */
@Entity
@Table(name = "mfa_usuarios")
public class VerificacionDosPasos {

    @Id
    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Column(name = "secreto_cifrado", nullable = false)
    private String secretoCifrado;

    @Column(nullable = false)
    private boolean activa;

    /** Último paso de 30 s aceptado: un código de ese paso o de uno anterior ya no sirve. */
    @Column(name = "ultimo_paso")
    private Long ultimoPaso;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "activada_en")
    private Instant activadaEn;

    protected VerificacionDosPasos() {
    }

    public VerificacionDosPasos(UUID usuarioId, String secretoCifrado, Instant creadoEn) {
        this.usuarioId = usuarioId;
        this.secretoCifrado = secretoCifrado;
        this.creadoEn = creadoEn;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getSecretoCifrado() {
        return secretoCifrado;
    }

    public void setSecretoCifrado(String secretoCifrado) {
        this.secretoCifrado = secretoCifrado;
    }

    public boolean isActiva() {
        return activa;
    }

    public void setActiva(boolean activa) {
        this.activa = activa;
    }

    public Long getUltimoPaso() {
        return ultimoPaso;
    }

    public void setUltimoPaso(Long ultimoPaso) {
        this.ultimoPaso = ultimoPaso;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public void setCreadoEn(Instant creadoEn) {
        this.creadoEn = creadoEn;
    }

    public Instant getActivadaEn() {
        return activadaEn;
    }

    public void setActivadaEn(Instant activadaEn) {
        this.activadaEn = activadaEn;
    }
}
