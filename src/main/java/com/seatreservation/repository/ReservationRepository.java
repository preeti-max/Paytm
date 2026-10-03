package com.seatreservation.repository;

import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByShowIdAndUserId(UUID showId, String userId);

    @Query("SELECT COUNT(s) FROM Reservation r JOIN r.seats s WHERE r.show.id = :showId AND r.userId = :userId AND r.status = :status")
    long countConfirmedSeatsByUserAndShow(
        @Param("showId") UUID showId,
        @Param("userId") String userId,
        @Param("status") ReservationStatus status
    );
}
