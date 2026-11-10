package com.tripbus.identity.exception;
public class RefreshTokenException extends RuntimeException {
    private final String code;
    public RefreshTokenException(String code) { super(code); this.code=code; }
    public String getCode(){return code;}
}
