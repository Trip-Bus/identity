CREATE TABLE password_reset_tokens (
    id           UUID PRIMARY KEY,
    usuario_id   UUID NOT NULL REFERENCES usuarios (id),
    token_hash   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    expires_at   TIMESTAMP NOT NULL,
    used_at      TIMESTAMP
);

CREATE UNIQUE INDEX uq_password_reset_tokens_token_hash ON password_reset_tokens (token_hash);
CREATE INDEX idx_password_reset_tokens_usuario_id ON password_reset_tokens (usuario_id);
