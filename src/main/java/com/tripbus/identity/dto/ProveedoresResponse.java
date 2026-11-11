package com.tripbus.identity.dto;

/** GET /auth/providers: qué proveedores externos de inicio de sesión ofrece el frontend. */
public record ProveedoresResponse(Proveedor google) {

    /** {@code clientId} es público (va en el botón de Google); null si el proveedor está apagado. */
    public record Proveedor(boolean enabled, String clientId) {
    }
}
