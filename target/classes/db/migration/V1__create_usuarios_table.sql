CREATE TABLE usuarios (
    id                   UUID PRIMARY KEY,
    email                VARCHAR(255) NOT NULL,
    password_hash        VARCHAR(255) NOT NULL,
    documento_identidad  VARCHAR(50) NOT NULL,
    nombre               VARCHAR(255) NOT NULL,
    rol                  VARCHAR(20) NOT NULL DEFAULT 'PASAJERO',
    company_id           UUID,
    activo               BOOLEAN NOT NULL DEFAULT TRUE,
    fecha_creacion       TIMESTAMP NOT NULL,
    fecha_actualizacion  TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_usuarios_email ON usuarios (email);
CREATE UNIQUE INDEX uq_usuarios_documento_identidad ON usuarios (documento_identidad);
CREATE INDEX idx_usuarios_company_id ON usuarios (company_id);
