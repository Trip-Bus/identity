# Identity Service

Ciclo de vida de la identidad: registro, autenticación, sesiones, roles y permisos. Es el único
servicio que emite JWT (RS256) y el único que maneja contraseñas (Argon2id).

**HU:** US-001, US-096, US-005 · **Puerto:** 16001 · **BD:** `tripbus_identity`

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

## Ejecutar

```bash
# Llaves RSA (una vez)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
JWT_PRIVATE_KEY_PATH=private.pem JWT_PUBLIC_KEY_PATH=public.pem mvn spring-boot:run
# o, solo en desarrollo, con llaves efímeras:  mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Con `DEMO_USERS_ENABLED=true` se crean `admin@`, `operador@`, `conductor@` y `pasajero@tripbus.co`
(contraseña `DEMO_USERS_PASSWORD`, por defecto `TripBus2026`).

## Pruebas

`mvn test` (H2 en memoria). Toda la plataforma: ver el README de **API-gateway**.
