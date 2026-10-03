-- Indexes for high-concurrency lookups and join performance
CREATE INDEX idx_seats_show_id ON seats(show_id);
CREATE INDEX idx_seats_reservation_id ON seats(reservation_id);
CREATE INDEX idx_reservations_show_user ON reservations(show_id, user_id);
CREATE INDEX idx_idempotency_reservation ON idempotency_keys(reservation_id);
