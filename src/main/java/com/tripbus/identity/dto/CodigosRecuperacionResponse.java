package com.tripbus.identity.dto;

import java.util.List;

/** US-106: los 10 códigos de recuperación; se muestran una sola vez (solo se guardan sus hashes). */
public record CodigosRecuperacionResponse(List<String> recoveryCodes) {
}
