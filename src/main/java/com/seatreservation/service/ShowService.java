package com.seatreservation.service;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.SeatResponse;
import com.seatreservation.dto.ShowResponse;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.entity.Show;
import com.seatreservation.exception.BadRequestException;
import com.seatreservation.exception.ResourceNotFoundException;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final int defaultPerUserLimit;

    public ShowService(
        ShowRepository showRepository,
        SeatRepository seatRepository,
        @Value("${app.reservation.default-per-user-limit:4}") int defaultPerUserLimit
    ) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.defaultPerUserLimit = defaultPerUserLimit;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Show name must not be blank");
        }
        if (request.seats() == null || request.seats().isEmpty()) {
            throw new BadRequestException("Seats list must not be empty");
        }
        if (request.pricePaise() == null || request.pricePaise() <= 0) {
            throw new BadRequestException("price_paise must be greater than 0");
        }
        int perUserLimit = request.perUserLimit() != null ? request.perUserLimit() : defaultPerUserLimit;
        if (perUserLimit <= 0) {
            throw new BadRequestException("per_user_limit must be greater than 0");
        }

        // Validate and normalize seat numbers
        List<String> normalizedSeats = new ArrayList<>();
        Set<String> seenSeats = new HashSet<>();
        for (String rawSeat : request.seats()) {
            if (rawSeat == null || rawSeat.isBlank()) {
                throw new BadRequestException("Seat identifier must not be blank");
            }
            String normalized = rawSeat.trim();
            if (!seenSeats.add(normalized)) {
                throw new BadRequestException("Duplicate seat identifier in request: " + normalized);
            }
            normalizedSeats.add(normalized);
        }

        Show show = new Show(UUID.randomUUID(), request.name().trim(), request.pricePaise(), perUserLimit);
        showRepository.save(show);

        List<Seat> seatEntities = new ArrayList<>();
        for (String seatNumber : normalizedSeats) {
            Seat seat = new Seat(UUID.randomUUID(), show, seatNumber, SeatStatus.AVAILABLE);
            seatEntities.add(seat);
        }
        seatRepository.saveAll(seatEntities);

        List<SeatResponse> seatResponses = seatEntities.stream()
            .map(s -> new SeatResponse(s.getId(), s.getSeatNumber(), s.getStatus().name().toLowerCase(), s.getHeldBy(), s.getReservationId()))
            .toList();

        return new ShowResponse(
            show.getId(),
            show.getName(),
            show.getPricePaise(),
            show.getPerUserLimit(),
            seatEntities.size(),
            seatEntities.size(), // available
            0,                   // held
            0,                   // confirmed
            seatResponses
        );
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = showRepository.findById(showId)
            .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        List<Seat> seats = seatRepository.findByShowIdOrderBySeatNumberAsc(showId);

        long available = 0;
        long held = 0;
        long confirmed = 0;

        List<SeatResponse> seatResponses = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            if (seat.getStatus() == SeatStatus.AVAILABLE) {
                available++;
            } else if (seat.getStatus() == SeatStatus.HELD) {
                held++;
            } else if (seat.getStatus() == SeatStatus.CONFIRMED) {
                confirmed++;
            }
            seatResponses.add(new SeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getStatus().name().toLowerCase(),
                seat.getHeldBy(),
                seat.getReservationId()
            ));
        }

        long totalSeats = seats.size();
        if (available + held + confirmed != totalSeats) {
            throw new IllegalStateException("Reconciliation failure: available(" + available + ") + held(" + held + ") + confirmed(" + confirmed + ") != total(" + totalSeats + ")");
        }

        return new ShowResponse(
            show.getId(),
            show.getName(),
            show.getPricePaise(),
            show.getPerUserLimit(),
            totalSeats,
            available,
            held,
            confirmed,
            seatResponses
        );
    }
}
