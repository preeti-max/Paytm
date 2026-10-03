package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveSeatRequest(
    @NotEmpty(message = "seats list must not be empty")
    @JsonProperty("seats")
    List<String> seats
) {
}
