package com.tripbus.identity.notification;

import com.tripbus.identity.util.Enmascarar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sin MAIL_HOST (IDE, pruebas o un entorno sin SMTP) no se envía nada: solo se deja constancia en el
 * log, con el correo enmascarado. El enlace lleva el token, que permite cambiar la contraseña o verificar
 * el correo, así que nunca se escribe en el log, en ningún nivel (HT-10). Para ver los enlaces en QA usa
 * un SMTP local (Mailpit o MailHog con MAIL_HOST). Lo crea MailConfig.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void enviarCorreoRecuperacion(String email, String nombre, String tokenPlano) {
        log.info("Correo de recuperación de contraseña para {} (sin SMTP: no se envía)", Enmascarar.email(email));
    }

    @Override
    public void enviarAvisoBloqueoCuenta(String email, String nombre, String tokenPlano) {
        log.info("Aviso de bloqueo de cuenta para {} (sin SMTP: no se envía)", Enmascarar.email(email));
    }

    @Override
    public void enviarCorreoVerificacion(String email, String nombre, String tokenPlano) {
        log.info("Correo de verificación para {} (sin SMTP: no se envía)", Enmascarar.email(email));
    }

    @Override
    public void enviarAvisoCambioContrasena(String email, String nombre) {
        log.info("Aviso de cambio de contraseña para {} (sin SMTP: no se envía)", Enmascarar.email(email));
    }
}
