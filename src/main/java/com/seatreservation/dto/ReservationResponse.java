package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
    @JsonProperty("reservation_id") UUID reservationId,
    @JsonProperty("show_id") UUID showId,
    @JsonProperty("user_id") String userId,
    @JsonProperty("seats") List<String> seats,
    @JsonProperty("amount_paise") long amountPaise,
    @JsonProperty("status") String status
) {
}
