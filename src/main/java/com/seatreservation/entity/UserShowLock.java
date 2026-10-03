package com.seatreservation.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_show_locks")
public class UserShowLock {

    @EmbeddedId
    private UserShowLockId id;

    protected UserShowLock() {
        // JPA constructor
    }

    public UserShowLock(UserShowLockId id) {
        this.id = id;
    }

    public UserShowLockId getId() {
        return id;
    }
}
