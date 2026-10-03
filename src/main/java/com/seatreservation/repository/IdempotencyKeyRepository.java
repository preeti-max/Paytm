package com.seatreservation.repository;

import com.seatreservation.entity.IdempotencyKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, UUID> {

    Optional<IdempotencyKeyRecord> findByShowIdAndUserIdAndIdempotencyKey(
        UUID showId,
        String userId,
        String idempotencyKey
    );
}
