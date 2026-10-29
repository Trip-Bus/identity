package com.tripbus.identity.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Envío real por SMTP (con MAIL_HOST). Cada correo va en texto plano y en HTML (multipart/alternative).
 *
 * <p>El envío corre en segundo plano ({@code executor}): la respuesta no espera al SMTP, así que tampoco
 * tarda más cuando el correo existe (no sirve para enumerar cuentas). Si falla solo se registra un WARN;
 * el token nunca se escribe en el log.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final PlantillasCorreo plantillas;
    private final Executor executor;

    public SmtpEmailSender(JavaMailSender mailSender, String from, PlantillasCorreo plantillas, Executor executor) {
        this.mailSender = mailSender;
        this.from = from;
        this.plantillas = plantillas;
        this.executor = executor;
    }

    @Override
    public void enviarCorreoRecuperacion(String email, String nombre, String tokenPlano) {
        enviar("recuperación de contraseña", email, plantillas.recuperacion(nombre, tokenPlano));
    }

    @Override
    public void enviarCorreoVerificacion(String email, String nombre, String tokenPlano) {
        enviar("verificación", email, plantillas.verificacion(nombre, tokenPlano));
    }

    @Override
    public void enviarAvisoCambioContrasena(String email, String nombre) {
        enviar("aviso de cambio de contraseña", email, plantillas.avisoCambioContrasena(nombre));
    }

    private void enviar(String tipo, String email, Correo correo) {
        try {
            executor.execute(() -> enviarAhora(tipo, email, correo));
        } catch (RejectedExecutionException e) {
            log.warn("No se pudo programar el correo de {} para {}: {}", tipo, email, e.getMessage());
        }
    }

    void enviarAhora(String tipo, String email, Correo correo) {
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensaje, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(correo.asunto());
            helper.setText(correo.texto(), correo.html());
            mailSender.send(mensaje);
            log.info("Correo de {} enviado a {}", tipo, email);
        } catch (MailException | MessagingException e) {
            log.warn("No se pudo enviar el correo de {} a {}: {}", tipo, email, e.getMessage());
        }
    }
}
