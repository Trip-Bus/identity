-- El documento de identidad ahora se cifra en reposo (AES-256-GCM) antes de guardarse: el valor
-- almacenado es nonce + texto cifrado + tag en base64, mas largo que el documento en texto plano.
ALTER TABLE usuarios ALTER COLUMN documento_identidad TYPE VARCHAR(255);
