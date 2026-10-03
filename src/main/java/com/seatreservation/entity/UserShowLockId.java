package com.seatreservation.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserShowLockId implements Serializable {

    private UUID showId;
    private String userId;

    public UserShowLockId() {
    }

    public UserShowLockId(UUID showId, String userId) {
        this.showId = showId;
        this.userId = userId;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserShowLockId that = (UserShowLockId) o;
        return Objects.equals(showId, that.showId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, userId);
    }
}
