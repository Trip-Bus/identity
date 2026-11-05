package com.tripbus.identity;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres y Redis compartidos ("singleton containers") por las cinco *IT que antes declaraban
 * cada una su propio par: 10 contenedores pasan a ser 2 para todo el build. A propósito NO se
 * anotan con @Container/@Testcontainers, para que JUnit no los detenga entre clases; Ryuk los
 * apaga al terminar la JVM de Maven. @ServiceConnection los registra igual, sin depender de esa
 * extensión.
 */
abstract class IntegrationContainers {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
