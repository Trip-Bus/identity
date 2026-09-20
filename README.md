# identity

Microservicio de identidad de TripBus (Java 21, Spring Boot 3.3.4, puerto 16001).

## Correr en local

1. Levanta PostgreSQL con Docker Compose (usa las mismas credenciales por defecto de
   `application.yml`, no requiere configuración adicional):
   ```bash
   docker compose up -d
   ```
2. Arranca la aplicación con el perfil `local` (obligatorio: sin perfil activo, Spring cae en el
   perfil `default`, que no está autorizado a generar llaves efímeras, y el arranque falla a
   propósito):
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
   Con el perfil `local`, las llaves JWT y la de cifrado se generan de forma efímera en memoria
   (con un warning en el log); no sobreviven a un reinicio. Flyway aplica las migraciones
   automáticamente.
3. Prueba el registro:
   ```bash
   curl -X POST http://localhost:16001/auth/register -H "Content-Type: application/json" \
     -d '{"email":"a@a.com","password":"Passw0rd1","documentoIdentidad":"CC-123","nombre":"Test"}'
   ```

Para correr solo los tests (no necesitan Postgres, usan H2 en memoria): `mvn test`.

## Seguridad (US-089): cifrado en tránsito y en reposo, manejo seguro de credenciales

### Variables de entorno

Todos los secretos se leen exclusivamente de variables de entorno (`${VAR}`), nunca de valores
literales en el repositorio. Ver [.env.example](.env.example) para la lista completa
(`DB_PASSWORD`, `JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH`, `IDENTITY_ENCRYPTION_KEY`).

- En los perfiles `local` y `test`, si falta la llave JWT o `IDENTITY_ENCRYPTION_KEY`, la
  aplicación genera una llave EFÍMERA en memoria para no bloquear el desarrollo (los datos
  cifrados no sobreviven a un reinicio).
- En los perfiles `demo` y `production`, esto está deshabilitado a propósito: si falta cualquiera
  de esos secretos (o `IDENTITY_ENCRYPTION_KEY` no decodifica a 32 bytes), el arranque falla de
  inmediato con un mensaje explícito.
- `DB_PASSWORD` no tiene valor por defecto en `application-demo.yml` / `application-production.yml`
  (a diferencia de `application.yml`, que trae un valor de conveniencia solo para desarrollo
  local): si falta, el arranque falla en vez de conectarse silenciosamente con una contraseña de
  desarrollo.

### Cifrado en reposo

El documento de identidad del usuario (`Usuario.documentoIdentidad`) se cifra con AES-256-GCM
antes de guardarse en base de datos, vía `DocumentoIdentidadConverter`
(`AttributeConverter` de JPA). La llave se deriva de `IDENTITY_ENCRYPTION_KEY`
(`EncryptionKeyProvider`).

El nonce de cada cifrado es determinista (HMAC-SHA256 del texto plano, con una sub-llave separada
de la de cifrado), no aleatorio: es necesario para que `existsByDocumentoIdentidad` y el índice
`UNIQUE` de la columna sigan detectando documentos duplicados sin tener que descifrar toda la
tabla.

### Cifrado en tránsito

En los perfiles `demo` y `production`, `SecurityConfig` exige HTTPS (`requiresChannel`) en todas
las rutas salvo el healthcheck (`/actuator/health/**`, `/actuator/info`). Como el propio
contenedor/ingress termina TLS, la aplicación confía en el header `X-Forwarded-Proto` a través de
`ForwardedHeaderFilter` (activado con `server.forward-headers-strategy: framework`), sin terminar
TLS ella misma.

Todas las respuestas incluyen `Strict-Transport-Security` y `X-Frame-Options: DENY`
(`SecurityConfig`); `X-Content-Type-Options: nosniff` y `Cache-Control: no-store` ya vienen
activados por defecto en Spring Security para todas las rutas, incluidas `/auth/**`.

### Logs

Ningún log de la aplicación imprime contraseñas, tokens de recuperación completos ni el documento
de identidad en texto plano. `LoggingEmailSender` (el stub de correo, ver su propio TODO) solo
registra un indicio corto del token de recuperación, no el valor completo. `logback-spring.xml`
mantiene los loggers de binding de parámetros SQL de Hibernate por debajo de `DEBUG` como defensa
adicional.
