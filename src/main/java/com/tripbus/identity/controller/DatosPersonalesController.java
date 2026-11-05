package com.tripbus.identity.controller;

import com.tripbus.identity.dto.DatosPersonalesResponse;
import com.tripbus.identity.dto.EliminarCuentaRequest;
import com.tripbus.identity.security.UsuarioAutenticado;
import com.tripbus.identity.service.DatosPersonalesService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** US-131 (Ley 1581): descargar o eliminar mis datos. Cualquier rol, siempre sobre la propia cuenta. */
@RestController
@RequestMapping("/users/me")
public class DatosPersonalesController {

    private final DatosPersonalesService datosPersonales;

    public DatosPersonalesController(DatosPersonalesService datosPersonales) {
        this.datosPersonales = datosPersonales;
    }

    @GetMapping("/data")
    public ResponseEntity<DatosPersonalesResponse> exportar(@AuthenticationPrincipal UsuarioAutenticado actual) {
        return ResponseEntity.ok(datosPersonales.exportar(actual.userId()));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> eliminar(@AuthenticationPrincipal UsuarioAutenticado actual,
                                         @Valid @RequestBody EliminarCuentaRequest request) {
        datosPersonales.eliminar(actual.userId(), request.password());
        return ResponseEntity.noContent().build();
    }
}
