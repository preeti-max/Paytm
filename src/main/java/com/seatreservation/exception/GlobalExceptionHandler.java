package com.seatreservation.exception;

import com.seatreservation.dto.ErrorResponse;
import com.seatreservation.filter.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainConflictException.class)
    public ResponseEntity<ErrorResponse> handleDomainConflict(DomainConflictException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Domain conflict: errorCode={}, message={}, request_id={}", ex.getErrorCode(), ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), requestId));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Resource not found: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), requestId));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("No resource found: resourcePath={}, request_id={}", ex.getResourcePath(), requestId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found: " + ex.getResourcePath(), requestId));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Bad request: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(ErrorCode.INVALID_REQUEST, ex.getMessage(), requestId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
        if (errorMessage.isBlank()) {
            errorMessage = "Validation failed for request";
        }
        log.warn("Validation error: details='{}', request_id={}", errorMessage, requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(ErrorCode.INVALID_REQUEST, errorMessage, requestId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Malformed JSON request: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(ErrorCode.INVALID_REQUEST, "Malformed JSON request body", requestId));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Missing required header: headerName={}, request_id={}", ex.getHeaderName(), requestId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(ErrorCode.INVALID_REQUEST, "Missing required header: " + ex.getHeaderName(), requestId));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Forbidden access: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse(ErrorCode.FORBIDDEN, ex.getMessage(), requestId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Access denied: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse(ErrorCode.FORBIDDEN, "Access denied", requestId));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.warn("Authentication failed: message={}, request_id={}", ex.getMessage(), requestId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse(ErrorCode.UNAUTHORIZED, "Authentication required or invalid token", requestId));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        String requestId = RequestIdFilter.getCurrentRequestId();
        log.error("Unexpected server error: request_id={}", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred", requestId));
    }
}
