package com.tripbus.identity.config;

import com.tripbus.identity.notification.EmailSender;
import com.tripbus.identity.notification.LoggingEmailSender;
import com.tripbus.identity.notification.PlantillasCorreo;
import com.tripbus.identity.notification.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.StringUtils;

import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Elige cómo salen los correos: con MAIL_HOST, {@link SmtpEmailSender}; sin él, {@link LoggingEmailSender}.
 * El JavaMailSender se arma aquí (no con spring.mail.*) y no se expone como bean: así un MAIL_HOST vacío no
 * crea un cliente SMTP apuntando a la nada ni un health check de correo que tumbe el readiness.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);
    private static final String TIMEOUT_MS = "5000";

    @Bean
    public PlantillasCorreo plantillasCorreo(@Value("${tripbus.frontend-url}") String frontendUrl) {
        return new PlantillasCorreo(frontendUrl);
    }

    @Bean
    public EmailSender emailSender(MailProperties properties, PlantillasCorreo plantillas,
                                   @Qualifier("applicationTaskExecutor") Executor executor) {
        if (!properties.enabled()) {
            log.warn("MAIL_HOST no está definido: los correos no se envían, solo quedan en el log");
            return new LoggingEmailSender(plantillas);
        }
        log.info("Correos por SMTP en {}:{}", properties.host(), properties.port());
        return new SmtpEmailSender(javaMailSender(properties), properties.from(), plantillas, executor);
    }

    static JavaMailSenderImpl javaMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MS);
        props.put("mail.smtp.timeout", TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MS);
        if (StringUtils.hasText(properties.username())) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }
}
