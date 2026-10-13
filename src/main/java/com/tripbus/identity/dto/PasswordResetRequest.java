package com.tripbus.identity.dto;

import com.tripbus.identity.util.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(

        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(message = "El correo electrónico no tiene un formato válido")
        String email) {

    /** Se normaliza al construir (antes de @Valid): " Ana@Mail.com " pasa la validación como ana@mail.com. */
    public PasswordResetRequest {
        email = EmailNormalizer.normalizar(email);
    }
}
