package com.tripbus.identity.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Respuestas de los errores del login con Google que no se provocan fácilmente de punta a punta. */
class GoogleExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void sinLlavesDeGoogleResponde503GoogleUnavailable() {
        ResponseEntity<ErrorResponse> respuesta = handler.handleGoogleNoDisponible(new GoogleNoDisponibleException());

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(respuesta.getBody().error().code()).isEqualTo("GOOGLE_UNAVAILABLE");
    }

    @Test
    void registroIncompletoAdmiteNombreNulo() {
        ResponseEntity<ErrorResponse> respuesta = handler.handleGoogleRegistroIncompleto(
                new GoogleRegistroIncompletoException("ana@gmail.com", null));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody().error().details()).containsEntry("email", "ana@gmail.com")
                .containsEntry("nombre", null);
    }
}
