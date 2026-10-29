# ---- Etapa 1: compilación (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Etapa 2: imagen de ejecución (solo JRE) ----
FROM eclipse-temurin:21-jre-alpine
# Parches de seguridad de Alpine más nuevos que los de la imagen base (Trivy): CVE-2026-111497 en
# brotli-libs (corregido en 1.2.1-r0). Se puede quitar cuando la imagen base ya los traiga.
RUN apk upgrade --no-cache brotli-libs
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
