package com.tripbus.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Uno o más campos no son válidos", details));
    }

    @ExceptionHandler(EmailYaRegistradoException.class)
    public ResponseEntity<ErrorResponse> handleEmailYaRegistrado(EmailYaRegistradoException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("EMAIL_ALREADY_REGISTERED", ex.getMessage()));
    }

    @ExceptionHandler(DocumentoYaRegistradoException.class)
    public ResponseEntity<ErrorResponse> handleDocumentoYaRegistrado(DocumentoYaRegistradoException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DOCUMENTO_ALREADY_REGISTERED", ex.getMessage()));
    }

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<ErrorResponse> handleCredencialesInvalidas(CredencialesInvalidasException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("INVALID_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(CuentaSuspendidaException.class)
    public ResponseEntity<ErrorResponse> handleCuentaSuspendida(CuentaSuspendidaException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("ACCOUNT_SUSPENDED", ex.getMessage()));
    }

    @ExceptionHandler(UsuarioNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> handleUsuarioNoEncontrado(UsuarioNoEncontradoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("USER_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(CompanyIdRequeridoException.class)
    public ResponseEntity<ErrorResponse> handleCompanyIdRequerido(CompanyIdRequeridoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("COMPANY_ID_REQUIRED", ex.getMessage()));
    }

    @ExceptionHandler(EmpresaNoEncontradaException.class)
    public ResponseEntity<ErrorResponse> handleEmpresaNoEncontrada(EmpresaNoEncontradaException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("COMPANY_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(EmpresaInactivaException.class)
    public ResponseEntity<ErrorResponse> handleEmpresaInactiva(EmpresaInactivaException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("COMPANY_INACTIVE", ex.getMessage()));
    }

    @ExceptionHandler(CatalogNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> handleCatalogNoDisponible(CatalogNoDisponibleException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("CATALOG_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(NoPuedeSuspenderseException.class)
    public ResponseEntity<ErrorResponse> handleNoPuedeSuspenderse(NoPuedeSuspenderseException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("CANNOT_SUSPEND_SELF", ex.getMessage()));
    }

    @ExceptionHandler(RolNoPermitidoException.class)
    public ResponseEntity<ErrorResponse> handleRolNoPermitido(RolNoPermitidoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Uno o más campos no son válidos",
                        Map.of("rol", ex.getMessage())));
    }

    /** Parámetro con un valor que no se puede convertir (p. ej. ?rol=CHOFER o ?companyId=abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTipoInvalido(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", "Uno o más campos no son válidos",
                        Map.of(ex.getName(), "Valor no válido")));
    }

    @ExceptionHandler(ContrasenaActualIncorrectaException.class)
    public ResponseEntity<ErrorResponse> handleContrasenaActualIncorrecta(ContrasenaActualIncorrectaException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_CURRENT_PASSWORD", ex.getMessage()));
    }

    @ExceptionHandler(DemasiadasSolicitudesException.class)
    public ResponseEntity<ErrorResponse> handleDemasiadasSolicitudes(DemasiadasSolicitudesException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("TOO_MANY_REQUESTS", ex.getMessage()));
    }

    @ExceptionHandler(TokenInvalidoOExpiradoException.class)
    public ResponseEntity<ErrorResponse> handleTokenInvalidoOExpirado(TokenInvalidoOExpiradoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("TOKEN_INVALID_OR_EXPIRED", ex.getMessage()));
    }
}
