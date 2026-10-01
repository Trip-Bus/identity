-- US-096: cierre de sesión. Se registran los tokens (jti) revocados hasta su expiración.
CREATE TABLE sesiones_revocadas (
    jti          VARCHAR(64) PRIMARY KEY,
    usuario_id   UUID NOT NULL REFERENCES usuarios (id),
    revocada_en  TIMESTAMP NOT NULL,
    expira_en    TIMESTAMP NOT NULL
);

CREATE INDEX idx_sesiones_revocadas_expira ON sesiones_revocadas (expira_en);

-- US-001 / Ley 1581 de 2012: evidencia de la aceptación de la política de datos.
CREATE TABLE consentimientos (
    id           UUID PRIMARY KEY,
    usuario_id   UUID NOT NULL REFERENCES usuarios (id),
    politica     VARCHAR(80) NOT NULL,
    version      VARCHAR(20) NOT NULL,
    aceptado_en  TIMESTAMP NOT NULL
);

CREATE INDEX idx_consentimientos_usuario ON consentimientos (usuario_id);
