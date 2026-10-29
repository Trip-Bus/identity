package com.tripbus.identity.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Sin SMTP: los tokens de recuperación y verificación nunca deben quedar en logs INFO (PROD). */
class LoggingEmailSenderTest {

    private static final String TOKEN = "token-secreto-123";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final LoggingEmailSender sender = new LoggingEmailSender(new PlantillasCorreo("http://localhost:5173"));
    private Level nivelOriginal;

    @BeforeEach
    void capturarLogs() {
        nivelOriginal = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restaurar() {
        logger.detachAppender(appender);
        logger.setLevel(nivelOriginal);
    }

    @Test
    void enInfoSoloDiceElCorreoSinToken() {
        logger.setLevel(Level.INFO);

        sender.enviarCorreoRecuperacion("ana@tripbus.co", "Ana", TOKEN);
        sender.enviarCorreoVerificacion("ana@tripbus.co", "Ana", TOKEN);

        assertThat(appender.list).hasSize(2).allSatisfy(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.INFO);
            assertThat(evento.getFormattedMessage()).contains("ana@tripbus.co").doesNotContain(TOKEN);
        });
    }

    @Test
    void elEnlaceDeRecuperacionConElTokenSoloApareceEnDebug() {
        logger.setLevel(Level.DEBUG);

        sender.enviarCorreoRecuperacion("ana@tripbus.co", "Ana", TOKEN);

        assertThat(appender.list)
                .filteredOn(evento -> evento.getFormattedMessage().contains(TOKEN))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(evento.getFormattedMessage())
                            .contains("http://localhost:5173/restablecer?token=" + TOKEN);
                });
    }

    @Test
    void elEnlaceDeVerificacionConElTokenSoloApareceEnDebug() {
        logger.setLevel(Level.DEBUG);

        sender.enviarCorreoVerificacion("ana@tripbus.co", "Ana", TOKEN);

        assertThat(appender.list)
                .filteredOn(evento -> evento.getFormattedMessage().contains(TOKEN))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(evento.getFormattedMessage())
                            .contains("http://localhost:5173/verificar-correo?token=" + TOKEN);
                });
    }

    @Test
    void elAvisoDeCambioDeContrasenaQuedaEnInfo() {
        logger.setLevel(Level.INFO);

        sender.enviarAvisoCambioContrasena("ana@tripbus.co", "Ana");

        assertThat(appender.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.INFO);
            assertThat(evento.getFormattedMessage()).contains("Aviso de cambio de contraseña", "ana@tripbus.co");
        });
    }
}
