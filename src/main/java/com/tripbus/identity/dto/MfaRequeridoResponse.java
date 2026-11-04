package com.tripbus.identity.dto;

/** US-106: respuesta del login cuando la cuenta tiene 2FA: sin tokens, solo el desafío (5 min, un uso). */
public record MfaRequeridoResponse(boolean mfaRequired, String mfaToken) {
}
