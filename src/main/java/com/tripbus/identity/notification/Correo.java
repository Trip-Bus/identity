package com.tripbus.identity.notification;

/** Un correo listo para enviar: asunto, cuerpo en texto plano y el mismo cuerpo en HTML. */
public record Correo(String asunto, String texto, String html) {
}
