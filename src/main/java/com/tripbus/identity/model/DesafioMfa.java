package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** US-106: el mfaToken que entrega el login cuando la cuenta tiene 2FA. Solo se guarda su hash. */
@Entity
@Table(name = "mfa_desafios")
public class DesafioMfa {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(nullable = false)
    private int intentos;

    @Column(name = "usado_en")
    private Instant usadoEn;

    protected DesafioMfa() {
    }

    public DesafioMfa(String tokenHash, UUID usuarioId, Instant expiraEn) {
        this.id = UUID.randomUUID();
        this.tokenHash = tokenHash;
        this.usuarioId = usuarioId;
        this.expiraEn = expiraEn;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }

    public int getIntentos() {
        return intentos;
    }

    public void setIntentos(int intentos) {
        this.intentos = intentos;
    }

    public Instant getUsadoEn() {
        return usadoEn;
    }

    public void setUsadoEn(Instant usadoEn) {
        this.usadoEn = usadoEn;
    }
}
