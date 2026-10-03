package com.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record SeatResponse(
    @JsonProperty("id") UUID id,
    @JsonProperty("seat_number") String seatNumber,
    @JsonProperty("status") String status,
    @JsonProperty("held_by") String heldBy,
    @JsonProperty("reservation_id") UUID reservationId
) {
}
