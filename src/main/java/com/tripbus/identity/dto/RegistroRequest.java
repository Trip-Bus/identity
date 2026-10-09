package com.tripbus.identity.dto;

import jakarta.validation.constraints.AssertTrue;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroRequest(

        @Schema(description = "Correo electrónico del usuario, único en el sistema",
                example = "pasajero@tripbus.com")
        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(message = "El correo electrónico no tiene un formato válido")
        String email,

        @Schema(description = "Contraseña en texto plano; se hashea con Argon2id antes de guardarse. "
                + "Mínimo 8 caracteres, al menos una mayúscula y un número",
                example = "Passw0rd1")
        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(
                regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
                message = "La contraseña debe incluir al menos una letra mayúscula y un número")
        String password,

        @Schema(description = "Documento de identidad, único en el sistema; se cifra (AES-256-GCM) "
                + "antes de guardarse",
                example = "CC-1001")
        @NotBlank(message = "El documento de identidad es obligatorio")
        String documentoIdentidad,

        @Schema(description = "Nombre completo del usuario", example = "Juan Pérez")
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
