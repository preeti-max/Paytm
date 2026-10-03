package com.seatreservation.service;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveSeatRequest;
import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationStatus;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.entity.Show;
import com.seatreservation.exception.BadRequestException;
import com.seatreservation.exception.DomainConflictException;
import com.seatreservation.exception.ErrorCode;
import com.seatreservation.exception.ResourceNotFoundException;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final com.seatreservation.repository.UserShowLockRepository userShowLockRepository;

    public ReservationService(
        ShowRepository showRepository,
        SeatRepository seatRepository,
        ReservationRepository reservationRepository,
        com.seatreservation.repository.UserShowLockRepository userShowLockRepository
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userShowLockRepository = userShowLockRepository;
    }

    @Transactional
    public ReservationResponse reserveSeats(
        UUID showId,
        String userId,
        ReserveSeatRequest request
    ) {
        if (request.seats() == null || request.seats().isEmpty()) {
            throw new BadRequestException("Seats list must not be empty");
        }

        // 1. Input normalization, duplicate validation, and deterministic ordering
        List<String> normalizedSeats = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawSeat : request.seats()) {
            if (rawSeat == null || rawSeat.isBlank()) {
                throw new BadRequestException("Seat identifier must not be blank");
            }
            String normalized = rawSeat.trim();
            if (!seen.add(normalized)) {
                throw new BadRequestException("Duplicate seat identifier in request: " + normalized);
            }
            normalizedSeats.add(normalized);
        }
        // Sort deterministically to prevent database lock-order deadlocks
        Collections.sort(normalizedSeats);

        // 2. Fetch Show
        Show show = showRepository.findById(showId)
            .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // 3. Acquire pessimistic lock on (showId, userId) to serialize per-user limit checks
        userShowLockRepository.insertIfNotExists(showId, userId);
        userShowLockRepository.findByIdForUpdate(new com.seatreservation.entity.UserShowLockId(showId, userId));

        // 4. Check user's current confirmed seat count against show per-user limit
        long currentConfirmedCount = reservationRepository.countConfirmedSeatsByUserAndShow(
            showId,
            userId,
            ReservationStatus.CONFIRMED
        );

        if (currentConfirmedCount + normalizedSeats.size() > show.getPerUserLimit()) {
            log.info("Per-user limit exceeded: userId={}, showId={}, current={}, requested={}, limit={}",
                userId, showId, currentConfirmedCount, normalizedSeats.size(), show.getPerUserLimit());
            throw new DomainConflictException(
                ErrorCode.PER_USER_LIMIT,
                "Exceeds per-user seat limit of " + show.getPerUserLimit() + " for this show"
            );
        }

        // 5. Acquire pessimistic write lock on the requested seats in deterministic order
        List<Seat> lockedSeats = seatRepository.findSeatsForUpdate(showId, normalizedSeats);

        // Check if all requested seats exist in the show
        if (lockedSeats.size() != normalizedSeats.size()) {
            log.warn("Reservation conflict: not all requested seats exist. Requested={}, Found={}",
                normalizedSeats, lockedSeats.stream().map(Seat::getSeatNumber).toList());
            throw new DomainConflictException(
                ErrorCode.SEAT_TAKEN,
                "One or more requested seats are unavailable"
            );
        }

        // 4. Verify all seats are currently AVAILABLE (All-or-Nothing semantics)
        for (Seat seat : lockedSeats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                log.info("Reservation conflict: seat {} is in status {}", seat.getSeatNumber(), seat.getStatus());
                throw new DomainConflictException(
                    ErrorCode.SEAT_TAKEN,
                    "One or more requested seats are unavailable"
                );
            }
        }

        // 5. Calculate total amount in integer paise
        long amountPaise = (long) lockedSeats.size() * show.getPricePaise();

        // 6. Create Confirmed Reservation
        Reservation reservation = new Reservation(
            UUID.randomUUID(),
            show,
            userId,
            amountPaise,
            ReservationStatus.CONFIRMED
        );
        reservation.setSeats(lockedSeats);
        reservationRepository.save(reservation);

        // 7. Update Seat statuses
        for (Seat seat : lockedSeats) {
            seat.setStatus(SeatStatus.CONFIRMED);
            seat.setHeldBy(userId);
            seat.setReservationId(reservation.getId());
        }
        seatRepository.saveAll(lockedSeats);

        log.info("Reservation confirmed: id={}, showId={}, userId={}, seats={}, amountPaise={}",
            reservation.getId(), showId, userId, normalizedSeats, amountPaise);

        return new ReservationResponse(
            reservation.getId(),
            showId,
            userId,
            normalizedSeats,
            amountPaise,
            "confirmed"
        );
    }
}
