package com.tripbus.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** US-105: una acción administrativa, con el estado antes y después (JSON) y el motivo. */
@Entity
@Table(name = "admin_audit_log")
public class RegistroAuditoria {

    @Id
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccionAuditoria accion;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "antes")
    private Map<String, Object> antes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "despues")
    private Map<String, Object> despues;

    @Column(length = 500)
    private String motivo;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    protected RegistroAuditoria() {
    }

    public RegistroAuditoria(UUID actorId, AccionAuditoria accion, UUID targetUserId, Map<String, Object> antes,
                             Map<String, Object> despues, String motivo, Instant creadoEn) {
        this.id = UUID.randomUUID();
        this.actorId = actorId;
        this.accion = accion;
        this.targetUserId = targetUserId;
        this.antes = antes;
        this.despues = despues;
        this.motivo = motivo;
        this.creadoEn = creadoEn;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public AccionAuditoria getAccion() {
        return accion;
    }

    public UUID getTargetUserId() {
        return targetUserId;
    }

    public Map<String, Object> getAntes() {
        return antes;
    }

    public Map<String, Object> getDespues() {
        return despues;
    }

    public String getMotivo() {
        return motivo;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
