package com.seatreservation.repository;

import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByShowIdAndUserId(UUID showId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT COUNT(s) FROM Reservation r JOIN r.seats s WHERE r.show.id = :showId AND r.userId = :userId AND r.status = :status")
    long countConfirmedSeatsByUserAndShow(
        @Param("showId") UUID showId,
        @Param("userId") String userId,
        @Param("status") ReservationStatus status
    );
}
