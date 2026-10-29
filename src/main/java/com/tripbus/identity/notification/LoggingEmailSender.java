package com.tripbus.identity.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sin MAIL_HOST (IDE, pruebas o un entorno sin SMTP) no se envía nada: solo se deja constancia en el
 * log. El enlace lleva el token, que permite cambiar la contraseña o verificar el correo: nunca a
 * nivel INFO (PROD), solo en DEBUG (IDE/QA). Lo crea MailConfig.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final PlantillasCorreo plantillas;

    public LoggingEmailSender(PlantillasCorreo plantillas) {
        this.plantillas = plantillas;
    }

    @Override
    public void enviarCorreoRecuperacion(String email, String nombre, String tokenPlano) {
        log.info("Correo de recuperación de contraseña enviado a {} (sin SMTP: solo log)", email);
        if (log.isDebugEnabled()) {
            log.debug("Enlace de recuperación para {} (solo DEBUG): {}", email, plantillas.enlaceRestablecer(tokenPlano));
        }
    }

    @Override
    public void enviarCorreoVerificacion(String email, String nombre, String tokenPlano) {
        log.info("Correo de verificación enviado a {} (sin SMTP: solo log)", email);
        if (log.isDebugEnabled()) {
            log.debug("Enlace de verificación para {} (solo DEBUG): {}", email, plantillas.enlaceVerificar(tokenPlano));
        }
    }

    @Override
    public void enviarAvisoCambioContrasena(String email, String nombre) {
        log.info("Aviso de cambio de contraseña enviado a {} (sin SMTP: solo log)", email);
    }
}
