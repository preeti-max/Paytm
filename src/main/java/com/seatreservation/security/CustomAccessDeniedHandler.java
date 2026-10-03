package com.seatreservation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.dto.ErrorResponse;
import com.seatreservation.exception.ErrorCode;
import com.seatreservation.filter.RequestIdFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        String requestId = RequestIdFilter.getCurrentRequestId();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse errorResponse = new ErrorResponse(
            ErrorCode.FORBIDDEN,
            "Access denied: insufficient permissions",
            requestId
        );

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
