package com.tripbus.identity.dto;

import java.util.List;

/** Página de resultados (page empieza en 0). Mismo formato que catalog, sin depender de Spring Data. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
