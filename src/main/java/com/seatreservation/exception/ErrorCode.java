package com.seatreservation.exception;

public final class ErrorCode {
    public static final String SEAT_TAKEN = "SEAT_TAKEN";
    public static final String PER_USER_LIMIT = "PER_USER_LIMIT";
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String RESERVATION_ALREADY_CANCELLED = "RESERVATION_ALREADY_CANCELLED";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    private ErrorCode() {
    }
}
