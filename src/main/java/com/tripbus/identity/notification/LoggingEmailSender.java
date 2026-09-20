package com.tripbus.identity.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// TODO: reemplazar por un proveedor de correo real (SendGrid, SES, SMTP, etc.) antes
// de producción. Este stub solo deja el enlace de recuperación en el log para no
// bloquear el desarrollo de US-004 en tener un proveedor de correo configurado.
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void enviarCorreoRecuperacion(String email, String tokenPlano) {
        // Nunca se imprime el token completo en el log (US-089): solo un indicio corto,
        // suficiente para depuración local, insuficiente para reutilizar el token.
        log.info("Enlace de recuperación de contraseña generado para {}: token={}...({} caracteres)",
                email, tokenPlano.substring(0, Math.min(6, tokenPlano.length())), tokenPlano.length());
    }
}
