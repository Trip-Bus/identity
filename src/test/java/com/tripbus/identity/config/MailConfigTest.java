package com.tripbus.identity.config;

import com.tripbus.identity.notification.LoggingEmailSender;
import com.tripbus.identity.notification.PlantillasCorreo;
import com.tripbus.identity.notification.SmtpEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class MailConfigTest {

    private final MailConfig config = new MailConfig();
    private final PlantillasCorreo plantillas = config.plantillasCorreo("http://localhost:5173");

    @Test
    void sinMailHostSeUsaElEnvioPorLog() {
        MailProperties sinHost = new MailProperties("", null, null, null, null);

        assertThat(sinHost.enabled()).isFalse();
        assertThat(sinHost.port()).isEqualTo(1025);
        assertThat(sinHost.from()).isEqualTo(MailProperties.FROM_POR_DEFECTO);
        assertThat(config.emailSender(sinHost, plantillas, Runnable::run)).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void conMailHostSeUsaElEnvioReal() {
        MailProperties conHost = new MailProperties("smtp.tripbus.co", 587, null, null, "Otro <otro@tripbus.co>");

        assertThat(conHost.enabled()).isTrue();
        assertThat(conHost.from()).isEqualTo("Otro <otro@tripbus.co>");
        assertThat(config.emailSender(conHost, plantillas, Runnable::run)).isInstanceOf(SmtpEmailSender.class);
    }

    @Test
    void sinUsuarioNoSeAutenticaYConUsuarioUsaAuthYStartTls() {
        JavaMailSenderImpl anonimo = MailConfig.javaMailSender(new MailProperties("localhost", 1025, "", null, null));
        assertThat(anonimo.getHost()).isEqualTo("localhost");
        assertThat(anonimo.getPort()).isEqualTo(1025);
        assertThat(anonimo.getUsername()).isNull();
        assertThat(anonimo.getJavaMailProperties()).doesNotContainKey("mail.smtp.auth")
                .containsEntry("mail.smtp.timeout", "5000");

        JavaMailSenderImpl autenticado =
                MailConfig.javaMailSender(new MailProperties("smtp.tripbus.co", 587, "user", "secreto", null));
        assertThat(autenticado.getUsername()).isEqualTo("user");
        assertThat(autenticado.getPassword()).isEqualTo("secreto");
        assertThat(autenticado.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true");
    }
}
