-- US-130: verificación del correo al registrarse. No verificarlo NO bloquea el login.
ALTER TABLE usuarios ADD COLUMN email_verificado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE usuarios ADD COLUMN email_verificado_en TIMESTAMP;

-- Las cuentas que ya existen (incluidos los usuarios demo de QA) quedan verificadas para no romper nada.
-- Los usuarios demo que se creen después los marca verificados DemoUsersInitializer.
UPDATE usuarios SET email_verificado = TRUE, email_verificado_en = now();

-- Tokens de verificación: un solo uso, vigencia de 24 h y, como en password_reset_tokens, solo el hash
-- SHA-256 del token (el token en claro solo viaja en el correo). Tabla propia y no una columna "tipo" en
-- password_reset_tokens: así un token de verificación nunca puede servir para cambiar una contraseña.
-- "reenvio" distingue los pedidos con POST /auth/verify-email/resend (máximo 3 por hora) del del registro.
CREATE TABLE email_verification_tokens (
    id           UUID PRIMARY KEY,
    usuario_id   UUID NOT NULL REFERENCES usuarios (id),
    token_hash   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    expires_at   TIMESTAMP NOT NULL,
    used_at      TIMESTAMP,
    reenvio      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX uq_email_verification_tokens_token_hash ON email_verification_tokens (token_hash);
CREATE INDEX idx_email_verification_tokens_usuario_creado ON email_verification_tokens (usuario_id, created_at);
