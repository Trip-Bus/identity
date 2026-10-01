package com.tripbus.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroRequest(

        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(message = "El correo electrónico no tiene un formato válido")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
                message = "La contraseña debe incluir al menos una letra mayúscula y un número")
        String password,

        @NotBlank(message = "El documento de identidad es obligatorio")
        String documentoIdentidad,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        // US-001 / Ley 1581 de 2012: el registro exige aceptar la política de tratamiento de datos.
        @NotNull(message = "Debe aceptar la política de tratamiento de datos personales")
        @AssertTrue(message = "Debe aceptar la política de tratamiento de datos personales")
        Boolean aceptaPoliticaDatos) {

    /** Compatibilidad: registro programático en el que la política ya fue aceptada. */
    public RegistroRequest(String email, String password, String documentoIdentidad, String nombre) {
        this(email, password, documentoIdentidad, nombre, Boolean.TRUE);
    }
}
