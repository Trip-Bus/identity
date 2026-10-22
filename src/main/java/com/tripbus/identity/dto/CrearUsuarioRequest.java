package com.tripbus.identity.dto;

import com.tripbus.identity.model.RolUsuario;
import com.tripbus.identity.util.EmailNormalizer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** US-103: alta directa de un OPERADOR o CONDUCTOR por un ADMIN (sin contraseña). */
public record CrearUsuarioRequest(

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(message = "El correo electrónico no tiene un formato válido")
        String email,

        @NotBlank(message = "El documento de identidad es obligatorio")
        String documentoIdentidad,

        @NotNull(message = "El rol es obligatorio")
        RolUsuario rol,

        UUID companyId) {

    /** Igual que en el registro: "Ana@Mail.com " se guarda como ana@mail.com. */
    public CrearUsuarioRequest {
        email = EmailNormalizer.normalizar(email);
    }
}
