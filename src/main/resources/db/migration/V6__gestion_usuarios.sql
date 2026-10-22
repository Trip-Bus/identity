-- US-102: búsqueda de usuarios por nombre, email y documento sin distinguir mayúsculas ni tildes.
-- La app guarda en "busqueda" esos tres campos normalizados (TextoBusqueda: sin tildes, en minúsculas)
-- y normaliza igual el texto buscado, así la consulta es un LIKE '%texto%' sobre una sola columna.
ALTER TABLE usuarios ADD COLUMN busqueda VARCHAR(600);

-- Relleno de las filas existentes con la misma regla. Se reemplazan las tildes antes de lower()
-- porque lower() no convierte letras acentuadas con el locale C (el de la imagen postgres:alpine).
UPDATE usuarios
   SET busqueda = lower(translate(
           nombre || ' ' || email || ' ' || documento_identidad,
           'ÁÀÄÂÉÈËÊÍÌÏÎÓÒÖÔÚÙÜÛÑÇáàäâéèëêíìïîóòöôúùüûñç',
           'AAAAEEEEIIIIOOOOUUUUNCaaaaeeeeiiiioooouuuunc'));

ALTER TABLE usuarios ALTER COLUMN busqueda SET NOT NULL;

-- pg_trgm es una extensión "trusted" (PostgreSQL 13+): la puede crear el dueño de la base.
-- El índice GIN trigram permite que LIKE '%texto%' no recorra toda la tabla.
-- Se instala en "public" y se califica el operador para que funcione aunque el search_path sea otro.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE INDEX idx_usuarios_busqueda_trgm ON usuarios USING gin (busqueda public.gin_trgm_ops);

-- Listado ordenado por fecha de creación descendente (el id desempata) y filtro por rol.
-- company_id ya tiene índice (V1).
CREATE INDEX idx_usuarios_fecha_creacion ON usuarios (fecha_creacion DESC, id DESC);
CREATE INDEX idx_usuarios_rol ON usuarios (rol);

-- US-103: un ADMIN puede crear la cuenta y aceptar la política de datos en nombre del usuario.
-- "origen" distingue ese caso de la aceptación del propio usuario y "otorgado_por" guarda qué ADMIN fue.
ALTER TABLE consentimientos ADD COLUMN origen VARCHAR(40) NOT NULL DEFAULT 'USUARIO';
ALTER TABLE consentimientos ADD COLUMN otorgado_por UUID REFERENCES usuarios (id);
