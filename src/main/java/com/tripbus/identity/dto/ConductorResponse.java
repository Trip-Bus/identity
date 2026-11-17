package com.tripbus.identity.dto;

import java.util.UUID;

/** US-121: conductor que el operador puede asignar a un viaje (sin documento ni datos personales). */
public record ConductorResponse(UUID id, String nombre, String email) {
}
