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

/** Sin SMTP: ni el token ni el correo completo quedan en el log, en ningún nivel (HT-10). */
class LoggingEmailSenderTest {

    private static final String TOKEN = "token-secreto-123";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final LoggingEmailSender sender = new LoggingEmailSender();
    private Level nivelOriginal;

    @BeforeEach
    void capturarLogs() {
        nivelOriginal = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restaurar() {
        logger.detachAppender(appender);
        logger.setLevel(nivelOriginal);
    }

    @Test
    void ningunCorreoDejaElTokenNiElCorreoCompletoAunqueElLogEsteEnDebug() {
        sender.enviarCorreoRecuperacion("ana@tripbus.co", "Ana", TOKEN);
        sender.enviarAvisoBloqueoCuenta("ana@tripbus.co", "Ana", TOKEN);
        sender.enviarCorreoVerificacion("ana@tripbus.co", "Ana", TOKEN);
        sender.enviarAvisoCambioContrasena("ana@tripbus.co", "Ana");

        assertThat(appender.list).hasSize(4).allSatisfy(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.INFO);
            assertThat(evento.getFormattedMessage()).contains("a***@tripbus.co")
                    .doesNotContain("ana@tripbus.co").doesNotContain(TOKEN).doesNotContain("token=");
        });
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).contains("recuperación"))
                .anySatisfy(m -> assertThat(m).contains("bloqueo"))
                .anySatisfy(m -> assertThat(m).contains("verificación"))
                .anySatisfy(m -> assertThat(m).contains("cambio de contraseña"));
    }
}
