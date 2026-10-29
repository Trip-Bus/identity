package com.tripbus.identity.notification;

import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Textos de los correos (en español y con tuteo) y enlaces al frontend ({@code tripbus.frontend-url}).
 * Las vigencias que se mencionan son las de PasswordResetService (15 min) y VerificacionEmailService (24 h).
 * Todo dato del usuario que va en el HTML se escapa: el nombre lo escribe la persona al registrarse.
 */
public class PlantillasCorreo {

    static final String RUTA_RESTABLECER = "/restablecer?token=";
    static final String RUTA_VERIFICAR = "/verificar-correo?token=";
    static final String RUTA_RECUPERAR = "/recuperar";

    private static final String FIRMA = "El equipo de TripBus";

    private final String frontendUrl;

    public PlantillasCorreo(String frontendUrl) {
        this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    }

    public String enlaceRestablecer(String tokenPlano) {
        return frontendUrl + RUTA_RESTABLECER + tokenPlano;
    }

    public String enlaceVerificar(String tokenPlano) {
        return frontendUrl + RUTA_VERIFICAR + tokenPlano;
    }

    public String enlaceRecuperar() {
        return frontendUrl + RUTA_RECUPERAR;
    }

    public Correo recuperacion(String nombre, String tokenPlano) {
        return armar("Crea una nueva contraseña en TripBus", nombre,
                "Para crear una nueva contraseña, abre este enlace. Vence en 15 minutos y solo sirve una vez.",
                "Crear contraseña", enlaceRestablecer(tokenPlano),
                "Si no lo pediste, ignora este correo: tu contraseña sigue igual.");
    }

    public Correo verificacion(String nombre, String tokenPlano) {
        return armar("Confirma tu correo en TripBus", nombre,
                "Para confirmar que este correo es tuyo, abre este enlace. Vence en 24 horas.",
                "Confirmar correo", enlaceVerificar(tokenPlano),
                "Si no creaste una cuenta en TripBus, ignora este correo.");
    }

    public Correo avisoCambioContrasena(String nombre) {
        return armar("Tu contraseña de TripBus cambió", nombre,
                "Tu contraseña cambió. Si fuiste tú, no tienes que hacer nada. Si no fuiste tú, recupérala aquí:",
                "Recuperar mi contraseña", enlaceRecuperar(),
                "Por seguridad, las sesiones que tenías abiertas se cerraron.");
    }

    private static Correo armar(String asunto, String nombre, String mensaje, String boton, String enlace,
                                String nota) {
        String saludo = saludo(nombre);
        String texto = saludo + "\n\n" + mensaje + "\n" + enlace + "\n\n" + nota + "\n\n" + FIRMA + "\n";
        String html = """
                <!doctype html>
                <html lang="es">
                <body style="font-family: Arial, sans-serif; color: #1f2937; line-height: 1.5;">
                <p>%s</p>
                <p>%s</p>
                <p><a href="%s" style="display: inline-block; padding: 10px 18px; background: #0f766e; \
                color: #ffffff; text-decoration: none; border-radius: 6px;">%s</a></p>
                <p style="font-size: 13px; color: #6b7280;">Si el botón no funciona, copia este enlace en tu \
                navegador:<br>%s</p>
                <p>%s</p>
                <p>%s</p>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(saludo), mensaje, HtmlUtils.htmlEscape(enlace), boton,
                HtmlUtils.htmlEscape(enlace), nota, FIRMA);
        return new Correo(asunto, texto, html);
    }

    /** "Hola, Ana." con el primer nombre; "Hola." si no hay nombre. */
    static String saludo(String nombre) {
        if (!StringUtils.hasText(nombre)) {
            return "Hola.";
        }
        return "Hola, " + nombre.trim().split("\\s+")[0] + ".";
    }
}
