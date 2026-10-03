package com.seatreservation;

import com.seatreservation.entity.*;
import com.seatreservation.repository.*;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
@Transactional
class SchemaAndEntityIntegrationTest {

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserShowLockRepository userShowLockRepository;

    @Test
    @DisplayName("Should apply Flyway migrations and persist Show, Seats, Reservation, Idempotency, and Lock")
    void testFlywaySchemaAndEntityMappings() {
        // 1. Create Show
        Show show = new Show(UUID.randomUUID(), "Rock Concert 2026", 25000L, 4);
        showRepository.save(show);

        // 2. Create Seats
        Seat seat1 = new Seat(UUID.randomUUID(), show, "A1", SeatStatus.AVAILABLE);
        Seat seat2 = new Seat(UUID.randomUUID(), show, "A2", SeatStatus.AVAILABLE);
        seatRepository.saveAll(List.of(seat1, seat2));

        assertThat(showRepository.findById(show.getId())).isPresent();
        assertThat(seatRepository.findByShowIdOrderBySeatNumberAsc(show.getId())).hasSize(2);

        // 3. Create Reservation
        Reservation reservation = new Reservation(
            UUID.randomUUID(),
            show,
            "user-100",
            25000L,
            ReservationStatus.CONFIRMED
        );
        reservation.setSeats(List.of(seat1));
        reservationRepository.save(reservation);

        // Update seat status
        seat1.setStatus(SeatStatus.CONFIRMED);
        seat1.setHeldBy("user-100");
        seat1.setReservationId(reservation.getId());
        seatRepository.save(seat1);

        // 4. Save Idempotency Key Record
        IdempotencyKeyRecord idempotencyKeyRecord = new IdempotencyKeyRecord(
            UUID.randomUUID(),
            show,
            "user-100",
            "key-abc-123",
            "dummy-request-sha256-hash",
            reservation
        );
        idempotencyKeyRepository.save(idempotencyKeyRecord);

        // 5. Test User Show Lock insert and fetch
        userShowLockRepository.insertIfNotExists(show.getId(), "user-100");
        var lock = userShowLockRepository.findByIdForUpdate(new UserShowLockId(show.getId(), "user-100"));
        assertThat(lock).isPresent();

        // 6. Verify Counts and queries
        long availableCount = seatRepository.countByShowIdAndStatus(show.getId(), SeatStatus.AVAILABLE);
        long confirmedCount = seatRepository.countByShowIdAndStatus(show.getId(), SeatStatus.CONFIRMED);
        long totalCount = seatRepository.countByShowId(show.getId());

        assertThat(availableCount).isEqualTo(1);
        assertThat(confirmedCount).isEqualTo(1);
        assertThat(availableCount + confirmedCount).isEqualTo(totalCount);

        long userConfirmedSeats = reservationRepository.countConfirmedSeatsByUserAndShow(
            show.getId(),
            "user-100",
            ReservationStatus.CONFIRMED
        );
        assertThat(userConfirmedSeats).isEqualTo(1);

        var retrievedIdempotency = idempotencyKeyRepository.findByShowIdAndUserIdAndIdempotencyKey(
            show.getId(),
            "user-100",
            "key-abc-123"
        );
        assertThat(retrievedIdempotency).isPresent();
        assertThat(retrievedIdempotency.get().getReservation().getId()).isEqualTo(reservation.getId());
    }
}
