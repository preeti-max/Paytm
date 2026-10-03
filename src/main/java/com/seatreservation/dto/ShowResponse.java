package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
    @JsonProperty("id") UUID id,
    @JsonProperty("name") String name,
    @JsonProperty("price_paise") long pricePaise,
    @JsonProperty("per_user_limit") int perUserLimit,
    @JsonProperty("total_seats") long totalSeats,
    @JsonProperty("available") long available,
    @JsonProperty("held") long held,
    @JsonProperty("confirmed") long confirmed,
    @JsonProperty("seats") List<SeatResponse> seats
) {
}
