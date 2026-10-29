package com.tripbus.identity.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Envío real con un JavaMailSender simulado; el ejecutor corre en el mismo hilo para poder verificar. */
class SmtpEmailSenderTest {

    private static final String TOKEN = "token-secreto-123";
    private static final String FROM = "TripBus <no-reply@tripbus.co>";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final MimeMessage mensaje = new MimeMessage(Session.getInstance(new Properties()));
    private final PlantillasCorreo plantillas = new PlantillasCorreo("http://localhost:5173");
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, FROM, plantillas, Runnable::run);

    private final Logger logger = (Logger) LoggerFactory.getLogger(SmtpEmailSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level nivelOriginal;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenReturn(mensaje);
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

    private String crudo() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mensaje.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void recuperacionSeEnviaEnTextoYHtmlConElRemitenteYSinTokenEnElLog() throws Exception {
        sender.enviarCorreoRecuperacion("ana@tripbus.co", "Ana Pérez", TOKEN);

        verify(mailSender).send(mensaje);
        assertThat(((InternetAddress) mensaje.getFrom()[0]).getAddress()).isEqualTo("no-reply@tripbus.co");
        assertThat(((InternetAddress) mensaje.getFrom()[0]).getPersonal()).isEqualTo("TripBus");
        assertThat(mensaje.getAllRecipients()[0]).hasToString("ana@tripbus.co");
        assertThat(mensaje.getSubject()).isEqualTo("Crea una nueva contraseña en TripBus");
        assertThat(crudo()).contains("multipart/alternative", "text/plain", "text/html");

        assertThat(appender.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.INFO);
            assertThat(evento.getFormattedMessage()).contains("ana@tripbus.co").doesNotContain(TOKEN);
        });
    }

    @Test
    void verificacionYAvisoTambienSeEnvian() throws Exception {
        sender.enviarCorreoVerificacion("luis@tripbus.co", "Luis", TOKEN);
        assertThat(mensaje.getSubject()).isEqualTo("Confirma tu correo en TripBus");

        sender.enviarAvisoCambioContrasena("luis@tripbus.co", "Luis");
        assertThat(mensaje.getSubject()).isEqualTo("Tu contraseña de TripBus cambió");

        assertThat(appender.list).hasSize(2).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(TOKEN));
    }

    @Test
    void siElSmtpFallaSoloQuedaUnWarnSinTokenYNoSeLanzaExcepcion() {
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));

        sender.enviarCorreoRecuperacion("ana@tripbus.co", "Ana", TOKEN);

        assertThat(appender.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.WARN);
            assertThat(evento.getFormattedMessage())
                    .contains("No se pudo enviar", "ana@tripbus.co", "Connection refused")
                    .doesNotContain(TOKEN);
        });
    }

    @Test
    void unErrorAlArmarElMensajeTambienQuedaComoWarn() throws Exception {
        MimeMessage roto = mock(MimeMessage.class);
        doThrow(new MessagingException("mensaje roto")).when(roto).setFrom(any(Address.class));
        when(mailSender.createMimeMessage()).thenReturn(roto);

        sender.enviarCorreoVerificacion("ana@tripbus.co", "Ana", TOKEN);

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(appender.list).singleElement()
                .extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void siElEjecutorRechazaElEnvioQuedaUnWarn() {
        SmtpEmailSender saturado = new SmtpEmailSender(mailSender, FROM, plantillas, tarea -> {
            throw new RejectedExecutionException("cola llena");
        });

        saturado.enviarAvisoCambioContrasena("ana@tripbus.co", "Ana");

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(appender.list).singleElement().satisfies(evento -> {
            assertThat(evento.getLevel()).isEqualTo(Level.WARN);
            assertThat(evento.getFormattedMessage()).contains("cola llena");
        });
    }
}
