package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
    @NotBlank(message = "user_id is required")
    @JsonProperty("user_id")
    String userId,

    @JsonProperty("role")
    String role
) {
}
