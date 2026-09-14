package com.tripbus.identity.notification;

public interface EmailSender {

    void enviarCorreoRecuperacion(String email, String tokenPlano);
}
