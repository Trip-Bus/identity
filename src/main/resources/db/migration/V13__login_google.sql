-- Inicio de sesión con Google (Google Identity Services, ID token).

-- Una cuenta creada con Google no tiene contraseña hasta que la defina con /auth/password-reset.
ALTER TABLE usuarios ALTER COLUMN password_hash DROP NOT NULL;

-- "sub" del ID token de Google: identifica a la cuenta de Google aunque cambie su correo.
ALTER TABLE usuarios ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX uq_usuarios_google_sub ON usuarios (google_sub);
