package com.tripbus.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * SMTP de los correos de identity (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM), las
 * mismas variables que booking. Sin MAIL_HOST el envío real queda apagado; no es obligatorio en ningún entorno.
 */
@ConfigurationProperties(prefix = "tripbus.mail")
public record MailProperties(String host, Integer port, String username, String password, String from) {

    public static final String FROM_POR_DEFECTO = "TripBus <no-reply@tripbus.co>";

    public MailProperties {
        port = port != null ? port : 1025;
        from = StringUtils.hasText(from) ? from : FROM_POR_DEFECTO;
    }

    public boolean enabled() {
        return StringUtils.hasText(host);
    }
}
