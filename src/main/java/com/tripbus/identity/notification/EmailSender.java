package com.tripbus.identity.notification;

/**
 * Correos de identity. Implementaciones: {@link SmtpEmailSender} (envío real, con MAIL_HOST) y
 * {@link LoggingEmailSender} (sin MAIL_HOST: solo deja el enlace en el log). La elige MailConfig.
 * Ninguna lanza excepción si el envío falla: la respuesta al usuario debe ser la misma.
 */
public interface EmailSender {

    /** US-004/US-125 (y US-103, para que la persona defina su contraseña): enlace de recuperación. */
    void enviarCorreoRecuperacion(String email, String nombre, String tokenPlano);

    /** US-126: avisa del bloqueo e incluye un enlace de recuperación de un solo uso. */
    void enviarAvisoBloqueoCuenta(String email, String nombre, String tokenPlano);

    /** US-130: enlace para confirmar que el correo es de la persona. */
    void enviarCorreoVerificacion(String email, String nombre, String tokenPlano);

    /** US-129: aviso de que la contraseña cambió, con el enlace para recuperarla si no fue la persona. */
    void enviarAvisoCambioContrasena(String email, String nombre);
}
