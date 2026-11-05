-- US-106: verificación en dos pasos (TOTP, RFC 6238) para ADMIN y OPERADOR.

-- Una fila por usuario. El secreto va cifrado con AES-GCM (MFA_ENCRYPTION_KEY); "activa" es falso entre
-- /2fa/setup y /2fa/enable. "ultimo_paso" es el último paso de 30 s aceptado: un código ya usado se rechaza.
CREATE TABLE mfa_usuarios (
    usuario_id       UUID PRIMARY KEY REFERENCES usuarios (id),
    secreto_cifrado  VARCHAR(255) NOT NULL,
    activa           BOOLEAN      NOT NULL DEFAULT FALSE,
    ultimo_paso      BIGINT,
    creado_en        TIMESTAMP    NOT NULL,
    activada_en      TIMESTAMP
);

-- Códigos de recuperación: solo el hash SHA-256; cada uno sirve una vez.
CREATE TABLE mfa_codigos_recuperacion (
    id           UUID PRIMARY KEY,
    usuario_id   UUID        NOT NULL REFERENCES usuarios (id),
    codigo_hash  VARCHAR(64) NOT NULL,
    usado_en     TIMESTAMP
);

CREATE INDEX idx_mfa_codigos_usuario ON mfa_codigos_recuperacion (usuario_id);

-- Desafíos del login con 2FA (mfaToken): un solo uso, 5 minutos y como máximo 5 códigos fallidos.
CREATE TABLE mfa_desafios (
    id          UUID PRIMARY KEY,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    usuario_id  UUID        NOT NULL REFERENCES usuarios (id),
    expira_en   TIMESTAMP   NOT NULL,
    intentos    INTEGER     NOT NULL DEFAULT 0,
    usado_en    TIMESTAMP
);

CREATE INDEX idx_mfa_desafios_expira ON mfa_desafios (expira_en);
