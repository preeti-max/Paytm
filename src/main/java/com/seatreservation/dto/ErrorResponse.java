package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
    @JsonProperty("error") String error,
    @JsonProperty("message") String message,
    @JsonProperty("request_id") String requestId
) {
}
