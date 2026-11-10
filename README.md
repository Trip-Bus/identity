# Identity Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_identity&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_identity)

Ciclo de vida de la identidad: registro, autenticación, sesiones, roles y permisos. Es el único
servicio que emite JWT (RS256) y el único que maneja contraseñas (Argon2id).

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-001, US-096, US-005, US-101 a US-106, US-125 a US-131 · HT-10 · **Puerto:** 18001 · **BD:** `tripbus_identity` (puerto QA 18101)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/auth/register` | Crea cuenta PASAJERO. Exige `aceptaPoliticaDatos: true` (Ley 1581) → 201 + JWT. Envía el correo de verificación (US-130). |
| POST | `/auth/login` | Access token (60 min por defecto) y refresh token (14 días). Tras 5 fallos por email normalizado en 15 min, bloquea 15 min. No exige el correo verificado. Si la cuenta tiene la verificación en dos pasos activa responde 200 `{mfaRequired: true, mfaToken}` **sin tokens** (US-106). |
| POST | `/auth/2fa` | Público (US-106): segundo paso del login. `{mfaToken, code}` (código de 6 dígitos de la app) o `{mfaToken, recoveryCode}` → 200 con los mismos tokens del login. El `mfaToken` sirve una vez y vence en 5 min; 5 códigos fallidos lo invalidan. Errores: 401 `INVALID_MFA_CODE` (código incorrecto, vencido o ya usado) o `MFA_TOKEN_INVALID`. |
| POST | `/auth/refresh` | Público: `{refreshToken}` rota el refresh token y emite un access token con rol, `companyId` y `emailVerificado` actuales. |
| POST | `/auth/logout` | Revoca el token actual (claim `jti`) y la familia del refresh token; admite `{refreshToken}` opcional. Sin body, revoca refresh tokens de esa cuenta creados antes del access token. |
| GET/POST | `/auth/validate` | Valida un JWT (lo usa el API Gateway). Headers `X-User-Id`, `X-User-Role`. Rechaza (401) los tokens emitidos antes del último cambio de contraseña. |
| POST | `/auth/password-reset/request` · `/confirm` | Recuperación de contraseña (US-125): `request {email}` → siempre 202 con el mismo mensaje, exista o no el correo; si existe, llega el enlace `{FRONTEND_URL}/restablecer?token=…` (vence en 15 min, un solo uso). `confirm {token, nuevaPassword}` → 200; además deja el correo verificado (el enlace llegó a ese correo). Token inválido, usado o vencido → 400 `TOKEN_INVALID_OR_EXPIRED`. |
| POST | `/auth/verify-email` | Público (US-130): `{token}` del enlace `{FRONTEND_URL}/verificar-correo?token=…` (vence en 24 h, un solo uso) → 200 y `emailVerificado = true`. Inválido, usado o vencido → 400 `TOKEN_INVALID_OR_EXPIRED`. |
| POST | `/auth/verify-email/resend` | Autenticado (US-130): reenvía el enlace → 202. Si el correo ya está verificado → 200 sin enviar nada. Máximo 3 reenvíos por hora → si no, 429 `TOO_MANY_REQUESTS`. |
| GET | `/users/me` | Mi perfil, cualquier rol (US-128): `{id, email, emailVerificado, nombre, telefono, documentoIdentidad, rol, companyId, fechaCreacion}`. |
| PUT | `/users/me` | Editar mi perfil (US-128): `{nombre, telefono}`. `telefono` es opcional (vacío o `null` lo borra) y debe ser un celular colombiano: 10 dígitos que empiezan por 3. El email, el documento, el rol y la empresa no se editan aquí (se ignoran). |
| GET | `/users/me/data` | Descargar mis datos (US-131, Ley 1581), cualquier rol: `{perfil, rol, companyId, emailVerificado, emailVerificadoEn, consentimientos[], sesionesActivas[], verificacionDosPasos, generadoEn}`. Nunca incluye hashes, secretos ni tokens. Ver [Política de datos](#política-de-datos-personales-ley-1581). |
| POST | `/users/me/delete` | Eliminar mi cuenta (US-131): `{password, confirmacion: "ELIMINAR"}` → 204. Contraseña incorrecta → 400 `INVALID_CURRENT_PASSWORD`; sin `"ELIMINAR"` → 400 `VALIDATION_ERROR`; el único ADMIN activo → 409 `LAST_ADMIN`. |
| POST | `/users/me/2fa/setup` | ADMIN u OPERADOR (US-106): genera el secreto → `{secret, otpauthUri}` (el frontend muestra `otpauthUri` como QR). Repetirlo antes de activar reemplaza el secreto. Ya activa → 409 `MFA_ALREADY_ENABLED`. |
| POST | `/users/me/2fa/enable` | ADMIN u OPERADOR: `{code}` → 200 `{recoveryCodes: [10 códigos]}` (se muestran una sola vez). Código inválido → 400 `INVALID_MFA_CODE`; sin setup → 409 `MFA_NOT_SET_UP`. |
| POST | `/users/me/2fa/disable` | `{password, code}` → 204. Contraseña incorrecta → 400 `INVALID_CURRENT_PASSWORD`; código inválido → 400 `INVALID_MFA_CODE`; sin 2FA → 409 `MFA_NOT_ENABLED`. |
| GET | `/admin/audit?actorId=&targetUserId=&accion=&desde=&hasta=&page=&size=` | Auditoría de acciones administrativas (ADMIN, US-105). `accion`: `CAMBIAR_ROL`, `CREAR_USUARIO`, `SUSPENDER`, `REACTIVAR`, `DESBLOQUEAR` o `ELIMINAR_CUENTA`; `desde` (incluido) y `hasta` (excluido) en ISO-8601 (`2026-11-03T00:00:00Z`). Del más reciente al más antiguo; `size` por defecto 20, máximo 100. Cada registro: `{id, actorId, accion, targetUserId, antes, despues, motivo, creadoEn}`. |
| POST | `/users/me/password` | Cambiar mi contraseña (US-129): `{passwordActual, passwordNueva}` → 200 con un **token nuevo** (mismo formato que el login) para que la sesión actual siga abierta. La nueva cumple las reglas del registro (≥ 8, una mayúscula y un número). Actual incorrecta → 400 `INVALID_CURRENT_PASSWORD`. Los tokens emitidos antes dejan de valer, se deja `tripbus:pwd-changed:{userId}` en Redis y llega un correo de aviso. |
| GET | `/users/{id}` · `/users/{id}/roles` | Datos públicos / roles (el propio usuario o ADMIN). |
| GET | `/users?rol=&companyId=&activo=&q=&page=&size=` | Listar y buscar usuarios (ADMIN, US-102). `q` busca en nombre, email y documento sin distinguir mayúsculas ni tildes. Del más reciente al más antiguo; `size` por defecto 20, máximo 100. Respuesta `{content, page, size, totalElements, totalPages}`; nunca incluye `passwordHash`. |
| POST | `/users` | Crear OPERADOR o CONDUCTOR (ADMIN, US-103): `{nombre, email, documentoIdentidad, rol, companyId}` → 201. Sin contraseña: se envía el enlace de recuperación (mismo flujo y vigencia de `/auth/password-reset`) para que la persona defina la suya. El consentimiento queda aceptado por el ADMIN en nombre del usuario (`consentimientos.origen = ADMIN_EN_NOMBRE_DEL_USUARIO`). 409 `EMAIL_ALREADY_REGISTERED` / `DOCUMENTO_ALREADY_REGISTERED`. |
| PUT | `/users/{id}/role` | Cambiar rol (ADMIN). OPERADOR/CONDUCTOR requieren `companyId` válido en catalog (US-101). |
| PATCH | `/users/{id}/estado` | Suspender o reactivar (ADMIN, US-104): `{activo, motivo}`. Los tokens de una cuenta suspendida dejan de valer de inmediato (403 `ACCOUNT_SUSPENDED`). Un ADMIN no puede suspenderse a sí mismo (400 `CANNOT_SUSPEND_SELF`). |
| GET | `/.well-known/jwks.json` | Llave pública: los demás servicios validan los JWT localmente. |
| DELETE | `/users/{id}/lock` | Desbloquea manualmente una cuenta (ADMIN). El restablecimiento de contraseña también la desbloquea. |
| GET | `/internal/users/{id}` | Servicio a servicio (`X-Internal-Token` = `INTERNAL_API_TOKEN`): `{id, nombre, rol, companyId, activo}`. Nunca devuelve email, documento ni `passwordHash`. Sin token o con token incorrecto → 401; inexistente → 404 `USER_NOT_FOUND`. El API Gateway no enruta `/internal/**`. |

Los refresh tokens guardan únicamente el hash SHA-256 de un valor aleatorio de 256 bits en `refresh_tokens`.
Cada renovación rota el token dentro de la misma familia; reutilizar un token consumido revoca toda la familia.
El frontend debe guardar el refresh token fuera del alcance de scripts de terceros. Cuando el gateway sirva
el frontend bajo el mismo dominio (Sprint 5), podrá migrarse a una cookie `HttpOnly`.

| Error | Respuesta |
|---|---|
| `ACCOUNT_LOCKED` | 423 con header `Retry-After`; mismo comportamiento para correos existentes e inexistentes. |
| `INVALID_REFRESH_TOKEN` | 401 si el refresh token venció o fue revocado. |
| `REFRESH_TOKEN_REUSED` | 401; la reutilización revoca toda la familia. |
| `ACCOUNT_SUSPENDED` | 403 al intentar renovar una cuenta suspendida (o eliminada). |
| `LAST_ADMIN` | 409: el único ADMIN activo no puede eliminar su cuenta. |
| `MFA_SETUP_REQUIRED` | 403: el rol exige verificación en dos pasos y aún no está activa (ver `MFA_REQUIRED_ROLES`). |
| `INVALID_MFA_CODE` · `MFA_TOKEN_INVALID` | 401 en `/auth/2fa`; `INVALID_MFA_CODE` es 400 en `enable`/`disable`. |
| `MFA_ALREADY_ENABLED` · `MFA_NOT_SET_UP` · `MFA_NOT_ENABLED` | 409 en `/users/me/2fa/*`. |

Las familias se revocan al cerrar sesión, cambiar o restablecer la contraseña y suspender la cuenta. Un job diario
elimina refresh tokens vencidos hace más de siete días.

### Política de datos personales (Ley 1581)

- **Qué guarda identity:** nombre, correo, documento, teléfono (opcional), rol y empresa, la evidencia de la
  aceptación de la política de datos (`consentimientos`), las sesiones abiertas (refresh tokens, solo su hash) y,
  si la activó, la verificación en dos pasos (secreto cifrado y hashes de los códigos de recuperación).
- **Descargar (`GET /users/me/data`):** todo lo anterior en JSON, sin hashes, secretos ni tokens. De cada
  sesión solo se entrega el id de la familia, la última renovación y el vencimiento.
- **Eliminar (`POST /users/me/delete`):** la fila no se borra (las reservas y la auditoría la referencian): se
  **anonimiza** en una sola transacción. `email = deleted+<uuid>@tripbus.invalid`, `nombre = "Usuario eliminado"`,
  documento y teléfono en `null`, `activo = false`, contraseña aleatoria y `password_changed_at` = ahora (todo token
  anterior deja de valer). Además se revocan todos los refresh tokens, se marca `revocado_en` en los
  consentimientos (la aceptación se conserva como evidencia), se borran el secreto y los códigos de la 2FA y
  se registra `ELIMINAR_CUENTA` en la auditoría (sin datos personales). Después se deja `tripbus:suspended:{userId}`
  en Redis y se publica `UserDeleted {userId}`: booking anonimiza sus propios datos con ese evento.
- El correo y el documento quedan libres: la persona puede volver a registrarse como una cuenta nueva.
- **Logs:** nunca llevan tokens, contraseñas, documentos ni correos completos (se enmascaran: `a***@tripbus.co`).

### Verificación en dos pasos (US-106)

TOTP de la RFC 6238 (HmacSHA1, 6 dígitos, pasos de 30 s), compatible con Google/Microsoft Authenticator y Authy,
implementado con `javax.crypto` sin dependencias nuevas. Solo ADMIN y OPERADOR pueden activarla.

1. `POST /users/me/2fa/setup` → el frontend muestra `otpauthUri` como QR (o el `secret` para escribirlo).
2. `POST /users/me/2fa/enable {code}` → la activa y entrega 10 códigos de recuperación de un solo uso.
3. Desde entonces `POST /auth/login` responde `{mfaRequired: true, mfaToken}` y los tokens se piden en `POST /auth/2fa`.

Se acepta el paso actual ±1 (±30 s de diferencia de reloj) y se guarda el último paso usado: un código ya usado
se rechaza aunque siga en la ventana. El secreto se guarda cifrado con AES-256-GCM (`MFA_ENCRYPTION_KEY`, ligado
al id del usuario); de los códigos de recuperación y del `mfaToken` solo se guarda el hash SHA-256.

Con `MFA_REQUIRED_ROLES` (propiedad `tripbus.identity.mfa.required-roles`, vacía por defecto), por ejemplo
`ADMIN`, un usuario con ese rol y sin la 2FA activa solo puede llamar a `/users/me/2fa/*` (y a `/auth/logout`):
todo lo demás, incluido `/auth/validate`, responde 403 `MFA_SETUP_REQUIRED`.

### Auditoría de acciones administrativas (US-105)

Tabla `admin_audit_log (id, actor_id, accion, target_user_id, antes, despues, motivo, creado_en)`, con `antes` y
`despues` en JSON (`jsonb`). Se escribe en **la misma transacción** que la acción (si la acción falla no queda
registro y viceversa): cambio de rol, crear OPERADOR/CONDUCTOR, suspender, reactivar (solo si el estado cambia),
desbloquear y eliminar cuenta (US-131, el actor es el propio usuario). `antes`/`despues` solo guardan `rol`,
`companyId` y `activo`, nunca datos personales. Se consulta con `GET /admin/audit`.

### Validación de la empresa en catalog (US-101)

Al asignar OPERADOR o CONDUCTOR (`PUT /users/{id}/role` y `POST /users`) identity consulta
`GET {CATALOG_URL}/internal/companies/{companyId}` con el header `X-Internal-Token` (`INTERNAL_API_TOKEN`).
Timeouts: 300 ms de conexión y 800 ms de lectura. Sin validar la empresa no se asigna el rol.

| Caso | Respuesta |
|---|---|
| Falta `companyId` | 400 `COMPANY_ID_REQUIRED` |
| Catalog responde 404 | 400 `COMPANY_NOT_FOUND` |
| Empresa con `estado` distinto de `ACTIVA` | 400 `COMPANY_INACTIVE` |
| Catalog no responde, timeout o error | 503 `CATALOG_UNAVAILABLE` |

### Claims del token

| Claim | Contenido |
|---|---|
| `sub` | Id (UUID) del usuario. |
| `role` | Rol: `PASAJERO`, `OPERADOR`, `CONDUCTOR` o `ADMIN`. |
| `companyId` | Empresa del OPERADOR/CONDUCTOR; se omite si el usuario no tiene empresa. |
| `email` | Correo del usuario tal como está guardado (booking lo usa para enviar el tiquete, US-033). |
| `emailVerificado` | `true` si la persona ya confirmó su correo (US-130). Es informativo: no bloquea el login ni ningún endpoint. Se actualiza en el siguiente token (login o cambio de contraseña). |

Además lleva `jti`, `iat`, `exp` y, en el header, `kid`. Un token cuyo `iat` es anterior al último cambio de
contraseña (`usuarios.password_changed_at`, al segundo) deja de valer en todos los endpoints y en `/auth/validate`.

### Correos

Los envía `SmtpEmailSender` si está `MAIL_HOST`; si no, `LoggingEmailSender` solo deja constancia en el log con
el correo enmascarado: el enlace con el token **nunca** se escribe en el log, en ningún nivel (HT-10). Para ver
los enlaces en QA usa Mailpit o MailHog (`MAIL_HOST=host.docker.internal`, `MAIL_PORT=1025`). Van en texto plano y HTML, en español y con
tuteo ("Hola, Ana. Para crear una nueva contraseña…"). El envío es en segundo plano: si el SMTP falla se
registra un WARN (sin el token) y la respuesta es la misma, así no revela si el correo existe.

| Correo | Cuándo | Enlace |
|---|---|---|
| Recuperación | `POST /auth/password-reset/request` y alta de OPERADOR/CONDUCTOR (`POST /users`, US-103). | `{FRONTEND_URL}/restablecer?token=…` (15 min) |
| Verificación | `POST /auth/register` y `POST /auth/verify-email/resend`. | `{FRONTEND_URL}/verificar-correo?token=…` (24 h) |
| Aviso de cambio | `POST /users/me/password`. "Tu contraseña cambió. Si no fuiste tú, recupérala aquí". | `{FRONTEND_URL}/recuperar` |

En la BD solo se guarda el hash SHA-256 de cada token (`password_reset_tokens` y `email_verification_tokens`).
Los usuarios que ya existían antes de V8 y los usuarios demo quedan con el correo verificado.

### Eventos que publica identity

Van al stream de Redis `tripbus:events` (`EVENTS_STREAM`). Cada entrada lleva `eventId`, `type`,
`source=identity`, `occurredAt`, `payload` (JSON) y, si la petición tenía una traza activa, `traceparent`
(W3C Trace Context) para que el consumidor continúe la misma traza. Son *best effort*: si Redis falla se registra un
WARN y la operación responde igual.

| `type` | Cuándo | `payload` |
|---|---|---|
| `UserRegistered` | `POST /auth/register` o `POST /users` (US-103) exitoso. | `userId`, `email`, `role`, `timestamp` |
| `UserLoggedIn` | `POST /auth/login` exitoso, o `POST /auth/2fa` si la cuenta tiene 2FA. | `userId`, `timestamp`, `ip` |
| `SessionRevoked` | `POST /auth/logout` (US-006). | `jti`, `userId` |
| `UserSuspended` | `PATCH /users/{id}/estado` con `activo: false` sobre una cuenta activa (US-104). | `userId`, `motivo` |
| `UserReactivated` | `PATCH /users/{id}/estado` con `activo: true` sobre una cuenta suspendida (US-104). | `userId`, `motivo` |
| `UserDeleted` | `POST /users/me/delete` (US-131). Booking anonimiza sus datos con este evento. | `userId` |

Claves de Redis que deja identity para el Realtime Gateway (sin consultar a identity):

| Clave | Cuándo | TTL | Fuente de verdad |
|---|---|---|---|
| `tripbus:revoked:{jti}` = `"1"` | Logout. | Lo que le quedaba al token (mínimo 1 s). | Tabla `sesiones_revocadas` |
| `tripbus:suspended:{userId}` = `"1"` | Suspensión (se borra al reactivar) y eliminación de la cuenta (US-131, no se borra). | Sin TTL. | Columna `usuarios.activo` |
| `tripbus:pwd-changed:{userId}` = epoch en segundos del cambio | Cambio de contraseña (US-129) o recuperación confirmada. El Realtime Gateway cierra las conexiones con `iat` menor. | Vida máxima de un token (`JWT_EXPIRATION_MINUTES`, 60 min). | Columna `usuarios.password_changed_at` |

Suspender una cuenta ya suspendida (o reactivar una activa) vuelve a escribir (o borrar) la clave,
para reparar una falla anterior de Redis, pero no repite el evento.

`EVENTS_ENABLED=false` apaga los eventos y las claves de revocación, suspensión y cambio de contraseña
(toda la integración con Redis).

## Flujo de ramas y entornos

```
rama de trabajo --PR--> develop (QA) --PR de release--> main (PROD)
```

- `main` sigue siendo la rama por defecto en GitHub, pero **los PR de trabajo van con base `develop`**.
- `develop` es QA y `main` es PROD: a `main` solo llega código por un PR de release desde `develop`.

| Evento | Pruebas + JaCoCo + Trivy | SonarQube Cloud | Imagen publicada en GHCR |
|---|---|---|---|
| PR con base `develop` | ✔ | ✔ (quality gate) | no |
| push a `develop` (merge de un PR) | ✔ | ✔ (quality gate) | `:qa`, `:develop`, `:sha-<corto>` |
| PR con base `main` (release) | ✔ | — | no |
| push a `main` (merge del release) | ✔ | — | `:prod`, `:latest`, `:sha-<corto>` |
| `workflow_dispatch` | ✔ | solo si se lanza sobre `develop` | no |

**Por qué Sonar solo corre en `develop`:** el plan Free de SonarQube Cloud analiza una única rama,
y la rama principal del proyecto en Sonar es `develop`. Lo que llega a `main` es exactamente el
código que ya pasó el quality gate en `develop`, así que en `main` el CI hace `mvn verify` sin Sonar
y lo indica con un aviso (`::notice::`). El control de cobertura de JaCoCo (`jacoco:check`) sí corre
en todas las ramas.

**Cómo hacer un release:**

1. Asegúrate de que el último CI de `develop` esté en verde (pruebas, quality gate y Trivy) y de
   haber probado la imagen `:qa`.
2. Abre un PR con base `main` y rama de origen `develop` (título p. ej. `release: <resumen>`).
3. Cuando el CI del PR pase, haz merge (sin squash, para que `main` y `develop` compartan historial).
   El push a `main` publica la imagen `:prod` / `:latest`.

## Entornos (IDE / QA / PROD)

| Entorno | Cómo corre | Perfil Spring | Secretos |
|---|---|---|---|
| IDE | `mvn spring-boot:run` contra los contenedores de QA | (ninguno) | valores por defecto de QA en `application.yml` |
| QA | Docker Compose en tu computador | `qa` (+ `desplegado`) | `.env` (copia de `.env.example`) |
| PROD | Docker Compose en el servidor | `prod` (+ `desplegado`) | solo en el servidor |

Puertos de este repo (el contenedor escucha en el mismo puerto que publica):

| Servicio | QA (publicado) | Dentro de Docker | PROD |
|---|---|---|---|
| identity | 18001 | 18001 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_identity` / usuario `identity` | 18101 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18001/actuator/health
```

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/identity` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local `identity:qa-local`; para
usar la de QA ya publicada: `docker pull ghcr.io/trip-bus/identity:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop identity` (libera el 18001) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18101` (BD) y
`localhost:18100` (Redis) con las contraseñas de QA. Para las llaves JWT usa el perfil `local`
(llaves efímeras) o define `JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH` (ver abajo).

### Variables obligatorias

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | qa | prod |
|---|---|---|
| `DB_PASSWORD` | ✔ | ✔ |
| `REDIS_PASSWORD` | ✔ | ✔ |
| `JWT_PRIVATE_KEY_PATH` | ✔ | ✔ |
| `JWT_PUBLIC_KEY_PATH` | ✔ | ✔ |
| `CATALOG_URL` | ✔ | ✔ |
| `INTERNAL_API_TOKEN` | ✔ | ✔ |
| `MFA_ENCRYPTION_KEY` | ✔ | ✔ |
| `DEMO_USERS_PASSWORD` | ✔ | — (sin usuarios demo) |

`MFA_ENCRYPTION_KEY` cifra los secretos de la verificación en dos pasos. En PROD usa `openssl rand -base64 32`
(Base64 de 32 bytes se usa tal cual; cualquier otro texto de al menos 16 caracteres pasa por SHA-256). Si se
cambia, las 2FA ya activadas dejan de funcionar. **Si ya tienes un `.env` de QA, agrégala** (ver `.env.example`).
Desde el IDE, sin la variable, se usa una llave aleatoria por arranque.

Opcionales con valor por defecto: `SERVER_PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_POOL_SIZE`, `REDIS_HOST`, `REDIS_PORT`, `JWT_EXPIRATION_MINUTES`. Desde el IDE, `CATALOG_URL` e
`INTERNAL_API_TOKEN` toman `http://localhost:18002` y `tripbus_internal_qa_token`, igual que en booking.

Correo (opcionales en todos los entornos, mismas variables que booking):

| Variable | Por defecto | Uso |
|---|---|---|
| `MAIL_HOST` | vacío | Servidor SMTP. Vacío = no se envía nada, el enlace queda en el log (solo `DEBUG`). |
| `MAIL_PORT` | `1025` | Puerto SMTP. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | vacío | Si hay usuario se activa `AUTH` y `STARTTLS`. |
| `MAIL_FROM` | `TripBus <no-reply@tripbus.co>` | Remitente. |
| `FRONTEND_URL` | `http://localhost:5173` | Base de los enlaces de los correos. |
| `IDENTITY_LOGIN_MAX_ATTEMPTS` | `5` | Intentos fallidos antes del bloqueo por email normalizado. |
| `IDENTITY_LOGIN_WINDOW` | `15m` | Ventana de conteo de intentos de login. |
| `IDENTITY_LOGIN_LOCKOUT` | `15m` | Duración del bloqueo de login. |
| `IDENTITY_REFRESH_TTL` | `14d` | Vigencia del refresh token. |
| `MFA_REQUIRED_ROLES` | vacío | Roles que deben activar la 2FA antes de usar la API (`ADMIN`, `ADMIN,OPERADOR`). |

Observabilidad (opcionales en todos los entornos):

| Variable | Por defecto | Uso |
|---|---|---|
| `LOG_FORMAT` | `text` (`json` en el perfil `prod`) | `json` = logs estructurados ECS en la consola; cualquier otro valor, texto legible. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | vacío | Collector OTLP/HTTP (p. ej. `http://tempo:4318`; se agrega `/v1/traces` si falta). Vacío = las trazas no se exportan. |
| `TRACING_SAMPLING` | `0.1` | Fracción de peticiones trazadas (`management.tracing.sampling.probability`). |
| `OPENAPI_SERVER_URL` | vacío | Servidor que declara el OpenAPI; con `http://localhost:18000` el Swagger del API Gateway prueba por el gateway. |

## Ejecutar sin Docker

```bash
# Llaves RSA (una vez)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
JWT_PRIVATE_KEY_PATH=private.pem JWT_PUBLIC_KEY_PATH=public.pem mvn spring-boot:run
# o, solo en desarrollo, con llaves efímeras:  mvn spring-boot:run -Dspring-boot.run.profiles=local
```

En QA (o con `DEMO_USERS_ENABLED=true`) se crean `admin@`, `operador@`, `conductor@` y `pasajero@tripbus.co`
(contraseña `DEMO_USERS_PASSWORD`, por defecto `TripBus2026`).

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18001/swagger-ui.html
- OpenAPI JSON: http://localhost:18001/v3/api-docs

Para probar endpoints protegidos: haz login en `/auth/login` y pega el `accessToken` en **Authorize**.

## Observabilidad (HT-10)

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- **Logs:** cada línea lleva `requestId`, `traceId` y `spanId`. Texto legible por defecto
  (`... INFO [requestId] [traceId,spanId] logger : mensaje`) y JSON ECS con `LOG_FORMAT=json` (por defecto en
  `prod`; el MDC sale como campos del JSON). El `requestId` reutiliza el header `X-Request-Id` (lo manda el API
  Gateway) o se genera uno, y se devuelve en la respuesta. Nunca se escriben tokens, contraseñas, documentos ni
  correos completos.
- **Trazas:** Micrometer Tracing con OpenTelemetry. Con `OTEL_EXPORTER_OTLP_ENDPOINT` se exportan por OTLP/HTTP
  (Tempo); vacío = apagado (no se crea el exportador). Muestreo `TRACING_SAMPLING` (0.1). El contexto viaja a
  catalog en el header `traceparent` del `RestClient` y en el campo `traceparent` de los eventos de Redis Streams.
- **Métricas de negocio** (en `/actuator/prometheus`, con `application="identity"`):

| Métrica | Etiquetas | Qué cuenta |
|---|---|---|
| `tripbus_logins_total` | `resultado`: `exito`, `credenciales_invalidas`, `cuenta_bloqueada`, `cuenta_suspendida`, `mfa_requerida`, `mfa_invalida` | Intentos de login y del segundo paso. |
| `tripbus_registros_total` | — | Cuentas creadas con `/auth/register`. |
| `tripbus_cuentas_bloqueadas_total` | — | Bloqueos por intentos fallidos (US-126). |
| `tripbus_refresh_total` | `resultado`: `exito`, `invalid_refresh_token`, `refresh_token_reused`, `account_suspended` | Renovaciones de sesión. |

## Pruebas

```bash
mvn -B verify      # unitarias + H2 + integración con PostgreSQL real (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias y de controlador con H2 (perfil `test`, Flyway apagado); corren con
  surefire en la fase `test`.
- `*IT`: corren con failsafe en la fase `integration-test` (Testcontainers: Docker abierto), separadas
  de las unitarias para que su JVM y su caché de contextos de Spring no convivan en el mismo fork.
  Las cinco comparten un único Postgres y un único Redis ("singleton containers", ver
  `IntegrationContainers`) en vez de uno por clase, para no agotar la RAM del host.
- `IdentityPostgresIT`: levanta PostgreSQL 16 con Testcontainers y corre las migraciones Flyway reales.
- `RevocacionRedisIT` y `GestionUsuariosIT`: PostgreSQL y Redis reales; `GestionUsuariosIT` simula
  catalog con un servidor HTTP real (US-101 a US-104).
- `CuentaYCorreoIT`: PostgreSQL y Redis reales y un SMTP en memoria (GreenMail): correos de verificación,
  recuperación y aviso, clave `tripbus:pwd-changed:*` y tokens viejos rechazados (US-125, US-128 a US-130).
- `DatosAuditoriaYDosPasosIT`: PostgreSQL y Redis reales: migraciones V10 a V12 (`jsonb`), eliminar cuenta con la
  clave `tripbus:suspended:*` y el evento `UserDeleted`, auditoría y login en dos pasos (US-105, US-106, US-131).
- `ObservabilidadTests` / `ExportadorOtlpTests`: métricas en `/actuator/prometheus` y trazas apagadas sin
  `OTEL_EXPORTER_OTLP_ENDPOINT` (encendidas con él). `TotpTest` usa los vectores de prueba de la RFC 6238.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_identity` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI (falsos positivos revisados en `.gitleaksignore`) |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-10-28) | Mínimo |
|---|---|---|
| Líneas | 96,5 % | `jacoco.minimo.lineas` = 0.95 |
| Ramas | 87,1 % | `jacoco.minimo.ramas` = 0.85 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Las mejores candidatas para subir ramas son
`JwtKeyProvider`, `SesionService` y `JwtAuthenticationFilter`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica que probar: `IdentityApplication`, `dto/**` (records), `model/**`
(entidades JPA de constructor/getters/setters y el enum `RolUsuario`) y `config/OpenApiConfig`.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué se acepta. Todas
**vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no se migró a
Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la versión
(ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos del CI (`SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

Se crean en Settings → Secrets and variables → Actions:

| Secreto | Valor | Si falta |
|---|---|---|
| `SONAR_TOKEN` | Token de SonarQube Cloud de la organización `trip-bus` | el CI hace `mvn verify` sin Sonar y deja un aviso |
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub | descarga sin autenticar (ver abajo) |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) | descarga sin autenticar (ver abajo) |

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`) y el build de la imagen (`maven`, `eclipse-temurin`).
Con los secretos de Docker Hub los jobs `secretos`, `build-test` y `docker` inician sesión antes de
descargar nada y usan el límite de una cuenta autenticada. Son **opcionales pero recomendados**: si
faltan, el CI descarga sin autenticar, deja un aviso y puede fallar por el límite. Gitleaks se
descarga desde GHCR (`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact
docker build -t identity:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml identity:local
```
