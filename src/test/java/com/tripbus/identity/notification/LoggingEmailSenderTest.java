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

/** El token de recuperación de contraseña nunca debe quedar en logs INFO (PROD). */
class LoggingEmailSenderTest {

    private static final String TOKEN = "token-secreto-123";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
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

        new LoggingEmailSender().enviarCorreoRecuperacion("ana@tripbus.co", TOKEN);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("ana@tripbus.co")
                .doesNotContain(TOKEN);
    }

    @Test
    void elTokenSoloApareceEnDebug() {
        logger.setLevel(Level.DEBUG);

        new LoggingEmailSender().enviarCorreoRecuperacion("ana@tripbus.co", TOKEN);

        assertThat(appender.list)
                .filteredOn(evento -> evento.getFormattedMessage().contains(TOKEN))
                .singleElement()
                .extracting(ILoggingEvent::getLevel)
                .isEqualTo(Level.DEBUG);
        assertThat(appender.list)
                .filteredOn(evento -> evento.getLevel() == Level.INFO)
                .allSatisfy(evento -> assertThat(evento.getFormattedMessage()).doesNotContain(TOKEN));
    }
}
