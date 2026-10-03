package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(
    @JsonProperty("token")
    String token
) {
}
