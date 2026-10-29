-- US-128: el usuario puede editar su nombre y su teléfono desde su perfil (PUT /users/me).
-- Celular colombiano: 10 dígitos empezando por 3 (la app lo valida; la BD lo refuerza). Es opcional.
ALTER TABLE usuarios ADD COLUMN telefono VARCHAR(10);
ALTER TABLE usuarios ADD CONSTRAINT ck_usuarios_telefono CHECK (telefono IS NULL OR telefono ~ '^3[0-9]{9}$');
