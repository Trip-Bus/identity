
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

# identity

Microservicio de registro, autenticación y control de acceso de usuarios para TripBus (venta de
tiquetes de buses intermunicipales).

`Java 21` · `Spring Boot 3.3.4` · `Maven` · `PostgreSQL` · `JWT RS256`

## Descripción general

`identity` gestiona el ciclo de vida de la cuenta de un usuario de TripBus: registro, login,
emisión/validación de JWT, roles y recuperación de contraseña. Todo dato sensible (documento de
identidad, contraseñas) se protege en tránsito y en reposo.

**Qué NO hace:** no gestiona rutas ni itinerarios de buses (eso es responsabilidad de `catalog`),
no gestiona reservas ni tiquetes (`booking`), no gestiona flota ni vehículos (`fleet`), y no
procesa pagos.

**Relación con otros microservicios:** `identity` es la única fuente de verdad de identidad y
roles. Emite tokens JWT firmados con RS256 al hacer login; los demás microservicios de TripBus
(`catalog`, `booking`, `fleet`, `backoffice`) los validan de forma independiente (sin llamar de
vuelta a `identity`) obteniendo la llave pública desde el endpoint JWKS
(`GET /.well-known/jwks.json`).

## Tecnologías utilizadas

| Categoría | Detalle |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.3.4 (`spring-boot-starter-web`, `starter-validation`) |
| ORM | Spring Data JPA / Hibernate (`spring-boot-starter-data-jpa`) |
| Base de datos | PostgreSQL (`org.postgresql:postgresql`) |
| Migraciones | Flyway (`flyway-core`, `flyway-database-postgresql`) |
| Seguridad | Spring Security (`spring-boot-starter-security`); JWT RS256 con `io.jsonwebtoken:jjwt` 0.13.0; JWKS con `com.nimbusds:nimbus-jose-jwt` 10.9.1 |
| Hashing de contraseñas | Argon2id (`spring-security-crypto`, `Argon2PasswordEncoder`) sobre `org.bouncycastle:bcprov-jdk18on` 1.78.1 |
| Cifrado en reposo | AES-256-GCM (JCE estándar, `javax.crypto`) vía `AttributeConverter` de JPA |
| Testing | JUnit 5 + `spring-boot-starter-test`, `spring-security-test`, H2 en memoria para tests |
| Cobertura de tests | No configurada todavía (no hay plugin JaCoCo en `pom.xml`) |
| Documentación de API | Swagger UI / OpenAPI 3 con `springdoc-openapi-starter-webmvc-ui` 2.6.0 |
| Contenedores | Docker Compose (`docker-compose.yml`, PostgreSQL local); `Dockerfile` es un placeholder pendiente |
| CI/CD | `.github/workflows/ci-cd.yml` es un placeholder pendiente |

## Estructura de carpetas

```
IDENTITY/
├── .github/
│   ├── workflows/
│   │   └── ci-cd.yml                  # Placeholder, pipeline aún no implementado
│   └── modernize/                     # Tooling de modernización de Java, no forma parte de la app
├── k8s/
│   └── deployment.yaml                # Placeholder, manifiesto aún no implementado
├── src/
│   ├── main/
│   │   ├── java/com/tripbus/identity/
│   │   │   ├── config/                # OpenApiConfig (documentación Swagger/OpenAPI)
│   │   │   ├── controller/            # Endpoints REST: AuthController, UsuarioController, PasswordResetController
│   │   │   ├── dto/                   # Records de request/response (RegistroRequest, LoginRequest, AuthResponse, ...)
│   │   │   ├── exception/             # Excepciones de dominio + GlobalExceptionHandler
│   │   │   ├── model/                 # Entidades JPA: Usuario, PasswordResetToken, RolUsuario
│   │   │   ├── notification/          # Envío de notificaciones: EmailSender (interfaz) y su stub de log
│   │   │   ├── repository/            # Repositorios Spring Data JPA (UsuarioRepository, PasswordResetTokenRepository)
│   │   │   ├── security/              # SecurityConfig, JWT (emisión/validación/JWKS), Argon2id, cifrado en reposo
│   │   │   ├── service/               # Lógica de negocio: AuthService, UsuarioService, PasswordResetService
│   │   │   └── IdentityApplication.java
│   │   └── resources/
│   │       ├── db/migration/          # Migraciones Flyway V1-V4
│   │       ├── application.yml        # Config base (perfiles local/test)
│   │       ├── application-demo.yml   # Overrides de seguridad para el perfil demo
│   │       ├── application-production.yml  # Overrides de seguridad para el perfil production
│   │       └── logback-spring.xml     # Config de logging
│   └── test/
│       ├── java/com/tripbus/identity/ # Tests unitarios y de integración (MockMvc)
│       └── resources/application-test.yml  # Config del perfil test (H2 en memoria)
├── docker-compose.yml                 # PostgreSQL local
├── Dockerfile                         # Placeholder, imagen aún no implementada
├── .env.example                       # Variables de entorno de ejemplo
└── pom.xml
```

## Variables de entorno

| Variable | Descripción | Obligatoria | Ejemplo |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Perfil activo (`local`, `test`, `demo`, `production`) | Sí en la práctica: sin perfil activo, Spring usa el perfil `default`, que no tiene autorización para generar llaves efímeras y el arranque falla | `local` |
| `DB_URL` | Host de PostgreSQL | No (default `localhost`) | `localhost` |
| `DB_USERNAME` | Usuario de PostgreSQL | No (default `tripbus`) | `tripbus` |
| `DB_PASSWORD` | Contraseña de PostgreSQL | No en `local`/`test` (default `tripbus_local_pw`); **sí** en `demo`/`production` (sin valor por defecto) | `tripbus_local_pw` |
| `JWT_PRIVATE_KEY_PATH` | Ruta a la llave privada RSA (PKCS8, PEM) para firmar los JWT | No en `local`/`test` (se genera un par efímero en memoria); **sí** en `demo`/`production` | `/secrets/jwt-private.pem` |
| `JWT_PUBLIC_KEY_PATH` | Ruta a la llave pública RSA (X.509, PEM), expuesta en el JWKS | Igual que `JWT_PRIVATE_KEY_PATH` | `/secrets/jwt-public.pem` |
| `JWT_KEY_ID` | `kid` del JWT y del JWKS | No (default `identity-key-1`) | `identity-key-1` |
| `JWT_EXPIRATION_MINUTES` | Minutos de validez del access token | No (default `60`) | `60` |
| `IDENTITY_ENCRYPTION_KEY` | Llave AES-256 en base64 (32 bytes) para cifrar el documento de identidad en reposo | No en `local`/`test` (se genera una llave efímera); **sí** en `demo`/`production` (falla si falta o no decodifica a 32 bytes) | generada con `openssl rand -base64 32` |
| `ARGON2_SALT_LENGTH` | Longitud de la sal Argon2id | No (default `16`) | `16` |
| `ARGON2_HASH_LENGTH` | Longitud del hash Argon2id | No (default `32`) | `32` |
| `ARGON2_PARALLELISM` | Paralelismo de Argon2id | No (default `1`) | `1` |
| `ARGON2_MEMORY` | Memoria de Argon2id en KB | No (default `19456`) | `19456` |
| `ARGON2_ITERATIONS` | Iteraciones de Argon2id | No (default `2`) | `2` |

Ver [.env.example](.env.example) para la lista completa con comentarios.

## Cómo ejecutar el proyecto localmente

**Prerrequisitos:** JDK 21, Maven 3.9+, Docker Desktop (para levantar PostgreSQL).

1. Clona el repositorio.
2. Revisa [.env.example](.env.example) y exporta las variables que necesites en tu shell (el
   proyecto no carga un archivo `.env` automáticamente; no hay ninguna librería de dotenv en
   `pom.xml`). Para desarrollo local normalmente no necesitas exportar nada: `application.yml`
   trae valores por defecto y las llaves JWT/cifrado se generan de forma efímera.
3. Levanta PostgreSQL con Docker Compose:
   ```bash
   docker compose up -d
   ```
4. Ejecuta la aplicación con el perfil `local` (obligatorio explícitamente, ver tabla de variables):
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
   Flyway aplica las migraciones (`V1` a `V4`) automáticamente al arrancar; no hace falta un paso
   manual.
5. La aplicación queda disponible en **`http://localhost:16001`**.

## Documentación de la API (Swagger)

Con la aplicación corriendo, la documentación interactiva (OpenAPI 3) queda disponible en:

- **Swagger UI:** `http://localhost:16001/swagger-ui.html` (redirige a `/swagger-ui/index.html`)
- **Especificación JSON:** `http://localhost:16001/v3/api-docs`

Ambas rutas son públicas (no requieren JWT). Para probar los endpoints protegidos (`/users/**`)
desde Swagger UI: obtén un `accessToken` con `POST /auth/login`, haz clic en **Authorize**, y pega
el token (sin el prefijo `Bearer `).

## Endpoints principales

| Método | Ruta | Descripción | Autenticación / Rol |
|---|---|---|---|
| POST | `/auth/register` | Registra un nuevo usuario | Pública |
| POST | `/auth/login` | Autentica y emite un access token JWT | Pública |
| POST | `/auth/password-reset/request` | Solicita un token de recuperación de contraseña | Pública |
| POST | `/auth/password-reset/confirm` | Confirma la recuperación y establece una nueva contraseña | Pública |
| GET | `/.well-known/jwks.json` | Expone la llave pública RSA (JWKS) para validar los JWT | Pública |
| GET | `/users/{id}` | Obtiene los datos de un usuario | Requiere JWT válido (cualquier rol) |
| PUT | `/users/{id}/role` | Cambia el rol (y `companyId`) de un usuario | Requiere JWT válido + rol `ADMIN` |


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
mvn test
```

Corre toda la suite (tests unitarios y de integración con MockMvc), usando H2 en memoria — no
requiere PostgreSQL. Actualmente **no hay un reporte de cobertura configurado**: `pom.xml` no
declara el plugin de JaCoCo.

## Despliegue

- **Docker:** existe un `Dockerfile` en la raíz, pero es un placeholder (`# TODO: configuración de
  Docker`) — todavía no define una imagen construible.
- **CI/CD:** existe un workflow en `.github/workflows/ci-cd.yml`, pero también es un placeholder
  (`# TODO: pipeline de CI/CD`) — no hay etapas de build, test, análisis de calidad, build de
  imagen ni despliegue implementadas aún.
- **Kubernetes:** `k8s/deployment.yaml` es igualmente un placeholder pendiente.

## Seguridad

- Autenticación stateless con JWT firmado en RS256; llave pública expuesta vía JWKS
  (`/.well-known/jwks.json`) para que otros microservicios validen tokens sin llamar a `identity`.
- Control de acceso por roles con `@PreAuthorize` (Spring Security method security).
- Hashing de contraseñas con Argon2id.
- Cifrado en tránsito: HTTPS exigido (`requiresChannel`) en los perfiles `demo`/`production`
  (salvo el healthcheck), asumiendo terminación TLS en el ingress/contenedor; cabeceras HSTS,
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` y `Cache-Control: no-store`.
- Cifrado en reposo del documento de identidad con AES-256-GCM (`AttributeConverter` de JPA).
- Todos los secretos (contraseña de BD, llaves JWT, llave de cifrado) se leen exclusivamente de
  variables de entorno; en `demo`/`production` el arranque falla si faltan, en vez de usar un
  valor por defecto inseguro.
- Los logs no imprimen contraseñas, tokens de recuperación completos ni el documento de identidad
  en texto plano.
