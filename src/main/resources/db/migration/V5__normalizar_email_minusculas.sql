-- Email canónico: sin espacios alrededor y en minúsculas (lo mismo que EmailNormalizer en la app).
-- Si dos cuentas existentes solo difieren en mayúsculas/espacios, la migración FALLA sin tocar
-- datos: hay que resolver esos duplicados a mano (decidir qué cuenta se conserva) y relanzarla.
DO $$
DECLARE
    duplicados TEXT;
BEGIN
    SELECT string_agg(email_normalizado || ' (' || cantidad || ' cuentas)', ', ' ORDER BY email_normalizado)
      INTO duplicados
      FROM (SELECT lower(btrim(email)) AS email_normalizado, count(*) AS cantidad
              FROM usuarios
             GROUP BY lower(btrim(email))
            HAVING count(*) > 1) d;

    IF duplicados IS NOT NULL THEN
        RAISE EXCEPTION 'V5 no puede normalizar los correos a minúsculas: hay cuentas cuyo correo solo difiere en mayúsculas o espacios: %. No se modificó ningún dato; unifique o corrija esas cuentas manualmente y vuelva a ejecutar la migración.', duplicados;
    END IF;
END $$;

UPDATE usuarios
   SET email = lower(btrim(email))
 WHERE email <> lower(btrim(email));

-- El índice único pasa a ser sobre lower(email): "Ana@Mail.com" y "ana@mail.com" no pueden coexistir
-- aunque alguien escriba directo en la BD. Las búsquedas por email usan lower(email) (UsuarioRepository).
DROP INDEX uq_usuarios_email;
CREATE UNIQUE INDEX uq_usuarios_email_lower ON usuarios (lower(email));
