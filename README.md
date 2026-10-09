# Identity Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_identity&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_identity)

Ciclo de vida de la identidad: registro, autenticación, sesiones, roles y permisos. Es el único
servicio que emite JWT (RS256) y el único que maneja contraseñas (Argon2id).

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-001, US-096, US-005 · **Puerto:** 18001 · **BD:** `tripbus_identity` (puerto QA 18101)

## API

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/auth/register` | Crea cuenta PASAJERO. Exige `aceptaPoliticaDatos: true` (Ley 1581) → 201 + JWT. |
| POST | `/auth/login` | JWT con rol, `companyId` y expiración (60 min). |
| POST | `/auth/logout` | Revoca el token actual (claim `jti`). |
| GET/POST | `/auth/validate` | Valida un JWT (lo usa el API Gateway). Headers `X-User-Id`, `X-User-Role`. |
| POST | `/auth/password-reset/request` · `/confirm` | Recuperación de contraseña. |
| GET | `/users/{id}` · `/users/{id}/roles` | Datos públicos / roles. |
| PUT | `/users/{id}/role` | Cambiar rol (ADMIN). OPERADOR/CONDUCTOR requieren `companyId`. |
| GET | `/.well-known/jwks.json` | Llave pública: los demás servicios validan los JWT localmente. |

**Publica:** `UserRegistered`, `UserLoggedIn` (best effort en Redis Streams).

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
| `DEMO_USERS_PASSWORD` | ✔ | — (sin usuarios demo) |

Opcionales con valor por defecto: `SERVER_PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`,
`DB_POOL_SIZE`, `REDIS_HOST`, `REDIS_PORT`, `JWT_EXPIRATION_MINUTES`.

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

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_identity` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI (falsos positivos revisados en `.gitleaksignore`) |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué se acepta. Todas
**vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no se migró a
Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la versión
(ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

El análisis de Sonar solo corre si el repo tiene el secreto `SONAR_TOKEN`; sin él, el CI hace
`mvn verify` y deja un aviso. Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.30.1 git /repo --redact
docker build -t identity:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml identity:local
```
