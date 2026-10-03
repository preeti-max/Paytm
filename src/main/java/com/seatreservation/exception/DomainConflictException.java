package com.seatreservation.exception;

public class DomainConflictException extends RuntimeException {
    private final String errorCode;

    public DomainConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
