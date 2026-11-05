# ---- Etapa 1: compilación (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Etapa 2: imagen de ejecución (solo JRE) ----
FROM eclipse-temurin:21-jre-alpine
# Parches de seguridad de Alpine más nuevos que los de la imagen base: Trivy falla con CRITICAL/HIGH que ya
# tienen arreglo (p. ej. brotli-libs CVE-2026-111497, libexpat CVE-2026-113351). Se actualiza TODO, no
# paquete por paquete. APK_CACHE_BUST: el CI pasa un valor por ejecución para que la caché de capas
# (type=gha) no reutilice un "apk upgrade" viejo y la imagen salga con los parches ya publicados.
ARG APK_CACHE_BUST=local
RUN echo "apk upgrade: $APK_CACHE_BUST" && apk upgrade --no-cache
RUN addgroup -S tripbus && adduser -S tripbus -G tripbus
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER tripbus
ARG GIT_COMMIT=local
ENV SERVER_PORT=18001 APP_COMMIT=${GIT_COMMIT}
EXPOSE 18001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=6 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
