CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES usuarios(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    familia_id UUID NOT NULL,
    creado_en TIMESTAMP NOT NULL,
    expira_en TIMESTAMP NOT NULL,
    usado_en TIMESTAMP,
    revocado_en TIMESTAMP
);
CREATE INDEX idx_refresh_tokens_user_family ON refresh_tokens(user_id, familia_id);
CREATE INDEX idx_refresh_tokens_expira ON refresh_tokens(expira_en);
