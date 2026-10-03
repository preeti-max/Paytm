package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateShowRequest(
    @NotBlank(message = "Show name must not be blank")
    @JsonProperty("name")
    String name,

    @NotEmpty(message = "Seats list must not be empty")
    @JsonProperty("seats")
    List<String> seats,

    @NotNull(message = "price_paise is required")
    @Min(value = 1, message = "price_paise must be greater than 0")
    @JsonProperty("price_paise")
    Long pricePaise,

    @Min(value = 1, message = "per_user_limit must be greater than 0")
    @JsonProperty("per_user_limit")
    Integer perUserLimit
) {
}
