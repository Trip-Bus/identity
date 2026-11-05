package com.tripbus.identity.dto;

/** US-106: secreto en Base32 (para escribirlo a mano) y la URI otpauth:// que el frontend muestra como QR. */
public record ConfigurarMfaResponse(String secret, String otpauthUri) {
}
