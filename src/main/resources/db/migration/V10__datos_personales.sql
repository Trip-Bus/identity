-- US-131 (Ley 1581 de 2012): eliminar mis datos.
-- La cuenta no se borra (las reservas y la auditoría la referencian): se anonimiza. El documento pasa a
-- NULL, así que deja de ser obligatorio; el índice único lo sigue cuidando para las cuentas activas
-- (PostgreSQL permite varios NULL en una columna UNIQUE).
ALTER TABLE usuarios ALTER COLUMN documento_identidad DROP NOT NULL;

-- Retiro del consentimiento: la evidencia de la aceptación se conserva y se marca cuándo se revocó.
ALTER TABLE consentimientos ADD COLUMN revocado_en TIMESTAMP;
