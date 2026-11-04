-- US-105: auditoría de las acciones administrativas (y de la eliminación de cuenta, US-131).
-- Sin llaves foráneas a usuarios a propósito: el registro debe sobrevivir a cualquier cambio de la cuenta.
-- "antes" y "despues" guardan solo el estado relevante (rol, empresa, activo), nunca datos personales.
CREATE TABLE admin_audit_log (
    id              UUID PRIMARY KEY,
    actor_id        UUID         NOT NULL,
    accion          VARCHAR(40)  NOT NULL,
    target_user_id  UUID,
    antes           JSONB,
    despues         JSONB,
    motivo          VARCHAR(500),
    creado_en       TIMESTAMP    NOT NULL
);

CREATE INDEX idx_admin_audit_log_creado ON admin_audit_log (creado_en DESC, id DESC);
CREATE INDEX idx_admin_audit_log_actor ON admin_audit_log (actor_id);
CREATE INDEX idx_admin_audit_log_target ON admin_audit_log (target_user_id);
CREATE INDEX idx_admin_audit_log_accion ON admin_audit_log (accion);
