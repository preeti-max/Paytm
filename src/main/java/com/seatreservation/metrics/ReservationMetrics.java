package com.seatreservation.metrics;

import com.seatreservation.entity.SeatStatus;
import com.seatreservation.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter confirmedCounter;
    private final Counter cancelledCounter;
    private final ConcurrentMap<String, Counter> declinedCounters = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry meterRegistry, SeatRepository seatRepository) {
        this.meterRegistry = meterRegistry;

        this.confirmedCounter = Counter.builder("reservations_confirmed")
            .description("Total number of successfully confirmed seat reservations")
            .register(meterRegistry);

        this.cancelledCounter = Counter.builder("reservations_cancelled")
            .description("Total number of cancelled seat reservations")
            .register(meterRegistry);

        // Dynamic Gauge reflecting actual database count of available seats
        Gauge.builder("seats_available", seatRepository, repo -> (double) repo.countByStatus(SeatStatus.AVAILABLE))
            .description("Current number of seats available in the database")
            .register(meterRegistry);
    }

    public void recordConfirmed() {
        confirmedCounter.increment();
    }

    public void recordCancelled() {
        cancelledCounter.increment();
    }

    public void recordDeclined(String reason) {
        declinedCounters.computeIfAbsent(reason, r ->
            Counter.builder("reservations_declined")
                .description("Total number of declined seat reservations")
                .tag("reason", r)
                .register(meterRegistry)
        ).increment();
    }
}
