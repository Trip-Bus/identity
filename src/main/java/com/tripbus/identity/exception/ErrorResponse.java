package com.tripbus.identity.exception;

import java.util.Map;

public record ErrorResponse(ErrorDetail error) {

    public record ErrorDetail(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorDetail(code, message, Map.of()));
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new ErrorDetail(code, message, details));
    }
}
