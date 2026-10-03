package com.seatreservation.repository;

import com.seatreservation.entity.UserShowLock;
import com.seatreservation.entity.UserShowLockId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserShowLockRepository extends JpaRepository<UserShowLock, UserShowLockId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM UserShowLock l WHERE l.id = :id")
    Optional<UserShowLock> findByIdForUpdate(@Param("id") UserShowLockId id);

    @Modifying
    @Query(value = "INSERT INTO user_show_locks (show_id, user_id) VALUES (:showId, :userId) ON CONFLICT (show_id, user_id) DO NOTHING", nativeQuery = true)
    void insertIfNotExists(@Param("showId") UUID showId, @Param("userId") String userId);
}
