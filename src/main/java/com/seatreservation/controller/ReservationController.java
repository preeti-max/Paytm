package com.seatreservation.controller;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveSeatRequest;
import com.seatreservation.security.UserPrincipal;
import com.seatreservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserveSeats(
        @PathVariable("id") UUID id,
        @AuthenticationPrincipal UserPrincipal principal,
        @Valid @RequestBody ReserveSeatRequest request
    ) {
        String userId = principal.getUserId();
        ReservationResponse response = reservationService.reserveSeats(id, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
