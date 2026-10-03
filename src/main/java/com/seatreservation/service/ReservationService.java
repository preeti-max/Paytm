package com.seatreservation.service;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveSeatRequest;
import com.seatreservation.entity.*;
import com.seatreservation.exception.BadRequestException;
import com.seatreservation.exception.DomainConflictException;
import com.seatreservation.exception.ErrorCode;
import com.seatreservation.exception.ResourceNotFoundException;
import com.seatreservation.metrics.ReservationMetrics;
import com.seatreservation.repository.IdempotencyKeyRepository;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import com.seatreservation.repository.UserShowLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserShowLockRepository userShowLockRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ReservationMetrics reservationMetrics;

    public ReservationService(
        ShowRepository showRepository,
        SeatRepository seatRepository,
        ReservationRepository reservationRepository,
        UserShowLockRepository userShowLockRepository,
        IdempotencyKeyRepository idempotencyKeyRepository,
        ReservationMetrics reservationMetrics
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userShowLockRepository = userShowLockRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.reservationMetrics = reservationMetrics;
    }

    @Transactional
    public ReservationResponse reserveSeats(
        UUID showId,
        String userId,
        String idempotencyKey,
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

        // Compute canonical request hash
        String requestHash = computeSha256Hash("seats=" + String.join(",", normalizedSeats));

        // 2. Fetch Show
        Show show = showRepository.findById(showId)
            .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // 3. Acquire pessimistic lock on (showId, userId) to serialize per-user operations
        userShowLockRepository.insertIfNotExists(showId, userId);
        userShowLockRepository.findByIdForUpdate(new UserShowLockId(showId, userId));

        // 4. Check Idempotency Record (inside user_show_lock serialization)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String trimmedKey = idempotencyKey.trim();
            Optional<IdempotencyKeyRecord> existingKeyOpt = idempotencyKeyRepository
                .findByShowIdAndUserIdAndIdempotencyKey(showId, userId, trimmedKey);

            if (existingKeyOpt.isPresent()) {
                IdempotencyKeyRecord record = existingKeyOpt.get();
                if (record.getRequestHash().equals(requestHash)) {
                    // Case 1: Same key + same body -> return original reservation
                    Reservation orig = record.getReservation();
                    reservationMetrics.recordDeclined("idempotent_replay");
                    log.info("Idempotent replay: key={}, reservationId={}, userId={}", trimmedKey, orig.getId(), userId);
                    List<String> origSeats = orig.getSeats().stream().map(Seat::getSeatNumber).sorted().toList();
                    return new ReservationResponse(
                        orig.getId(),
                        showId,
                        userId,
                        origSeats,
                        orig.getAmountPaise(),
                        orig.getStatus().name().toLowerCase()
                    );
                } else {
                    // Case 2: Same key + different body -> 409 IDEMPOTENCY_KEY_REUSED
                    reservationMetrics.recordDeclined("idempotency_mismatch");
                    log.warn("Idempotency key reused with mismatched body: key={}, userId={}, showId={}", trimmedKey, userId, showId);
                    throw new DomainConflictException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was already used with a different request payload"
                    );
                }
            }
        }

        // 5. Check user's current confirmed seat count against show per-user limit
        long currentConfirmedCount = reservationRepository.countConfirmedSeatsByUserAndShow(
            showId,
            userId,
            ReservationStatus.CONFIRMED
        );

        if (currentConfirmedCount + normalizedSeats.size() > show.getPerUserLimit()) {
            reservationMetrics.recordDeclined("per_user_limit");
            log.info("Per-user limit exceeded: userId={}, showId={}, current={}, requested={}, limit={}",
                userId, showId, currentConfirmedCount, normalizedSeats.size(), show.getPerUserLimit());
            throw new DomainConflictException(
                ErrorCode.PER_USER_LIMIT,
                "Exceeds per-user seat limit of " + show.getPerUserLimit() + " for this show"
            );
        }

        // 6. Acquire pessimistic write lock on the requested seats in deterministic order
        List<Seat> lockedSeats = seatRepository.findSeatsForUpdate(showId, normalizedSeats);

        // Check if all requested seats exist in the show
        if (lockedSeats.size() != normalizedSeats.size()) {
            reservationMetrics.recordDeclined("seat_taken");
            log.warn("Reservation conflict: not all requested seats exist. Requested={}, Found={}",
                normalizedSeats, lockedSeats.stream().map(Seat::getSeatNumber).toList());
            throw new DomainConflictException(
                ErrorCode.SEAT_TAKEN,
                "One or more requested seats are unavailable"
            );
        }

        // 7. Verify all seats are currently AVAILABLE (All-or-Nothing semantics)
        for (Seat seat : lockedSeats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                reservationMetrics.recordDeclined("seat_taken");
                log.info("Reservation conflict: seat {} is in status {}", seat.getSeatNumber(), seat.getStatus());
                throw new DomainConflictException(
                    ErrorCode.SEAT_TAKEN,
                    "One or more requested seats are unavailable"
                );
            }
        }

        // 8. Calculate total amount in integer paise
        long amountPaise = (long) lockedSeats.size() * show.getPricePaise();

        // 9. Create Confirmed Reservation
        Reservation reservation = new Reservation(
            UUID.randomUUID(),
            show,
            userId,
            amountPaise,
            ReservationStatus.CONFIRMED
        );
        reservation.setSeats(lockedSeats);
        reservationRepository.save(reservation);

        // 10. Update Seat statuses
        for (Seat seat : lockedSeats) {
            seat.setStatus(SeatStatus.CONFIRMED);
            seat.setHeldBy(userId);
            seat.setReservationId(reservation.getId());
        }
        seatRepository.saveAll(lockedSeats);

        // 11. Store Idempotency Key Record if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyKeyRecord idempotencyRecord = new IdempotencyKeyRecord(
                UUID.randomUUID(),
                show,
                userId,
                idempotencyKey.trim(),
                requestHash,
                reservation
            );
            try {
                idempotencyKeyRepository.save(idempotencyRecord);
            } catch (DataIntegrityViolationException e) {
                log.warn("Unique constraint on idempotency key triggered concurrently: key={}", idempotencyKey);
                Optional<IdempotencyKeyRecord> fallbackOpt = idempotencyKeyRepository
                    .findByShowIdAndUserIdAndIdempotencyKey(showId, userId, idempotencyKey.trim());
                if (fallbackOpt.isPresent()) {
                    Reservation orig = fallbackOpt.get().getReservation();
                    reservationMetrics.recordDeclined("idempotent_replay");
                    List<String> origSeats = orig.getSeats().stream().map(Seat::getSeatNumber).sorted().toList();
                    return new ReservationResponse(
                        orig.getId(),
                        showId,
                        userId,
                        origSeats,
                        orig.getAmountPaise(),
                        orig.getStatus().name().toLowerCase()
                    );
                }
                throw e;
            }
        }

        // Record confirmed metric
        reservationMetrics.recordConfirmed();

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

    @Transactional
    public ReservationResponse cancelReservation(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
            .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with id: " + reservationId));

        if (!reservation.getUserId().equals(userId)) {
            log.warn("Unauthorized cancellation attempt: reservationId={}, owner={}, requester={}",
                reservationId, reservation.getUserId(), userId);
            throw new com.seatreservation.exception.ForbiddenException(
                "You do not have permission to cancel this reservation"
            );
        }

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.warn("Double cancellation attempted: reservationId={}, userId={}", reservationId, userId);
            throw new DomainConflictException(
                ErrorCode.RESERVATION_ALREADY_CANCELLED,
                "Reservation has already been cancelled"
            );
        }

        // Transition reservation status CONFIRMED -> CANCELLED
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        // Fetch and lock associated seats in deterministic order to release them
        List<String> seatNumbers = reservation.getSeats().stream()
            .map(Seat::getSeatNumber)
            .sorted()
            .toList();

        List<Seat> seatsToRelease = seatRepository.findSeatsForUpdate(reservation.getShow().getId(), seatNumbers);

        for (Seat seat : seatsToRelease) {
            seat.setStatus(SeatStatus.AVAILABLE);
            seat.setHeldBy(null);
            seat.setReservationId(null);
        }
        seatRepository.saveAll(seatsToRelease);

        reservationMetrics.recordCancelled();

        log.info("Reservation cancelled successfully: reservationId={}, showId={}, userId={}, releasedSeats={}",
            reservationId, reservation.getShow().getId(), userId, seatNumbers);

        return new ReservationResponse(
            reservation.getId(),
            reservation.getShow().getId(),
            userId,
            seatNumbers,
            reservation.getAmountPaise(),
            "cancelled"
        );
    }

    private static String computeSha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedHash.length);
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
