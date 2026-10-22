# Identity Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_identity&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_identity)

Ciclo de vida de la identidad: registro, autenticación, sesiones, roles y permisos. Es el único
servicio que emite JWT (RS256) y el único que maneja contraseñas (Argon2id).

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-001, US-096, US-005, US-101 a US-104 · **Puerto:** 18001 · **BD:** `tripbus_identity` (puerto QA 18101)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/auth/register` | Crea cuenta PASAJERO. Exige `aceptaPoliticaDatos: true` (Ley 1581) → 201 + JWT. |
| POST | `/auth/login` | JWT con rol, `companyId` y expiración (60 min). |
| POST | `/auth/logout` | Revoca el token actual (claim `jti`). |
| GET/POST | `/auth/validate` | Valida un JWT (lo usa el API Gateway). Headers `X-User-Id`, `X-User-Role`. |
| POST | `/auth/password-reset/request` · `/confirm` | Recuperación de contraseña. |
| GET | `/users/{id}` · `/users/{id}/roles` | Datos públicos / roles (el propio usuario o ADMIN). |
| GET | `/users?rol=&companyId=&activo=&q=&page=&size=` | Listar y buscar usuarios (ADMIN, US-102). `q` busca en nombre, email y documento sin distinguir mayúsculas ni tildes. Del más reciente al más antiguo; `size` por defecto 20, máximo 100. Respuesta `{content, page, size, totalElements, totalPages}`; nunca incluye `passwordHash`. |
| POST | `/users` | Crear OPERADOR o CONDUCTOR (ADMIN, US-103): `{nombre, email, documentoIdentidad, rol, companyId}` → 201. Sin contraseña: se envía el enlace de recuperación (mismo flujo y vigencia de `/auth/password-reset`) para que la persona defina la suya. El consentimiento queda aceptado por el ADMIN en nombre del usuario (`consentimientos.origen = ADMIN_EN_NOMBRE_DEL_USUARIO`). 409 `EMAIL_ALREADY_REGISTERED` / `DOCUMENTO_ALREADY_REGISTERED`. |
| PUT | `/users/{id}/role` | Cambiar rol (ADMIN). OPERADOR/CONDUCTOR requieren `companyId` válido en catalog (US-101). |
| PATCH | `/users/{id}/estado` | Suspender o reactivar (ADMIN, US-104): `{activo, motivo}`. Los tokens de una cuenta suspendida dejan de valer de inmediato (403 `ACCOUNT_SUSPENDED`). Un ADMIN no puede suspenderse a sí mismo (400 `CANNOT_SUSPEND_SELF`). |
| GET | `/.well-known/jwks.json` | Llave pública: los demás servicios validan los JWT localmente. |
| GET | `/internal/users/{id}` | Servicio a servicio (`X-Internal-Token` = `INTERNAL_API_TOKEN`): `{id, nombre, rol, companyId, activo}`. Nunca devuelve email, documento ni `passwordHash`. Sin token o con token incorrecto → 401; inexistente → 404 `USER_NOT_FOUND`. El API Gateway no enruta `/internal/**`. |

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

Además lleva `jti`, `iat`, `exp` y, en el header, `kid`.

### Eventos que publica identity

Van al stream de Redis `tripbus:events` (`EVENTS_STREAM`). Cada entrada lleva `eventId`, `type`,
`source=identity`, `occurredAt` y `payload` (JSON). Son *best effort*: si Redis falla se registra un
WARN y la operación responde igual.

| `type` | Cuándo | `payload` |
|---|---|---|
| `UserRegistered` | `POST /auth/register` o `POST /users` (US-103) exitoso. | `userId`, `email`, `role`, `timestamp` |
| `UserLoggedIn` | `POST /auth/login` exitoso. | `userId`, `timestamp`, `ip` |
| `SessionRevoked` | `POST /auth/logout` (US-006). | `jti`, `userId` |
| `UserSuspended` | `PATCH /users/{id}/estado` con `activo: false` sobre una cuenta activa (US-104). | `userId`, `motivo` |
| `UserReactivated` | `PATCH /users/{id}/estado` con `activo: true` sobre una cuenta suspendida (US-104). | `userId`, `motivo` |

Claves de Redis que deja identity para el Realtime Gateway (sin consultar a identity):

| Clave | Cuándo | TTL | Fuente de verdad |
|---|---|---|---|
| `tripbus:revoked:{jti}` = `"1"` | Logout. | Lo que le quedaba al token (mínimo 1 s). | Tabla `sesiones_revocadas` |
| `tripbus:suspended:{userId}` = `"1"` | Suspensión; se borra al reactivar. | Sin TTL. | Columna `usuarios.activo` |

Suspender una cuenta ya suspendida (o reactivar una activa) vuelve a escribir (o borrar) la clave,
para reparar una falla anterior de Redis, pero no repite el evento.

`EVENTS_ENABLED=false` apaga los eventos y las claves de revocación y suspensión (toda la integración
con Redis).

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
| `DEMO_USERS_PASSWORD` | ✔ | — (sin usuarios demo) |

Opcionales con valor por defecto: `SERVER_PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_POOL_SIZE`, `REDIS_HOST`, `REDIS_PORT`, `JWT_EXPIRATION_MINUTES`. Desde el IDE, `CATALOG_URL` e
`INTERNAL_API_TOKEN` toman `http://localhost:18002` y `tripbus_internal_qa_token`, igual que en booking.

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

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Logs legibles con `[requestId]` en IDE/QA y
  JSON (con `requestId` y `"service":"identity"`) en PROD.

## Pruebas

```bash
mvn -B verify      # unitarias + H2 + integración con PostgreSQL real (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias y de controlador con H2 (perfil `test`, Flyway apagado).
- `IdentityPostgresIT`: levanta PostgreSQL 16 con Testcontainers y corre las migraciones Flyway reales.
- `RevocacionRedisIT` y `GestionUsuariosIT`: PostgreSQL y Redis reales; `GestionUsuariosIT` simula
  catalog con un servidor HTTP real (US-101 a US-104).

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

| Métrica | Medido (2026-10-22) | Mínimo |
|---|---|---|
| Líneas | 91,5 % | `jacoco.minimo.lineas` = 0.90 |
| Ramas | 83,5 % | `jacoco.minimo.ramas` = 0.80 |

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
