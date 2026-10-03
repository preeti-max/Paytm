package com.seatreservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ReserveSeatRequest;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.security.JwtService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
class ReservationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SeatRepository seatRepository;

    private String adminToken;
    private String user1Token;
    private String user2Token;

    @BeforeEach
    void setUp() {
        adminToken = "Bearer " + jwtService.generateToken("admin-user", "ADMIN");
        user1Token = "Bearer " + jwtService.generateToken("user-1", "USER");
        user2Token = "Bearer " + jwtService.generateToken("user-2", "USER");
    }

    private String createShow(String name, List<String> seats, long pricePaise, int perUserLimit) throws Exception {
        CreateShowRequest request = new CreateShowRequest(name, seats, pricePaise, perUserLimit);
        String res = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(res).get("id").asText();
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Should reserve single seat successfully")
    void testReserveSingleSeatSuccess() throws Exception {
        String showId = createShow("friday-show-1", List.of("A1", "A2", "A3"), 25000L, 4);

        ReserveSeatRequest reserveRequest = new ReserveSeatRequest(List.of("A1"));

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .header("Idempotency-Key", "key-single-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reserveRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reservation_id").isNotEmpty())
            .andExpect(jsonPath("$.show_id").value(showId))
            .andExpect(jsonPath("$.user_id").value("user-1"))
            .andExpect(jsonPath("$.seats", contains("A1")))
            .andExpect(jsonPath("$.amount_paise").value(25000))
            .andExpect(jsonPath("$.status").value("confirmed"));

        // Verify show reconciliation
        mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_seats").value(3))
            .andExpect(jsonPath("$.available").value(2))
            .andExpect(jsonPath("$.confirmed").value(1));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Should reserve multiple seats with correct amount in paise")
    void testReserveMultipleSeatsSuccess() throws Exception {
        String showId = createShow("saturday-show", List.of("A1", "A2", "A3"), 20000L, 4);

        ReserveSeatRequest reserveRequest = new ReserveSeatRequest(List.of("A2", "A1"));

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reserveRequest)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user_id").value("user-1"))
            .andExpect(jsonPath("$.seats", contains("A1", "A2")))
            .andExpect(jsonPath("$.amount_paise").value(40000))
            .andExpect(jsonPath("$.status").value("confirmed"));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - All-or-Nothing: Partial conflict rejects entire reservation and preserves state")
    void testAllOrNothingMultiSeatConflict() throws Exception {
        String showId = createShow("sunday-show", List.of("A1", "A2", "A3"), 25000L, 4);

        // 1. User 1 reserves A1
        ReserveSeatRequest req1 = new ReserveSeatRequest(List.of("A1"));
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        // 2. User 2 attempts to reserve A1 and A2
        ReserveSeatRequest req2 = new ReserveSeatRequest(List.of("A1", "A2"));
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user2Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("SEAT_TAKEN"))
            .andExpect(jsonPath("$.message", containsString("unavailable")));

        // 3. Verify A2 is STILL available and not held
        mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(2))
            .andExpect(jsonPath("$.confirmed").value(1));

        var seats = seatRepository.findByShowIdOrderBySeatNumberAsc(UUID.fromString(showId));
        assertThat(seats.stream().filter(s -> s.getSeatNumber().equals("A2")).findFirst().get().getStatus())
            .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Idempotency Case 1: Same key + same body returns original reservation")
    void testIdempotencyReplaySameBody() throws Exception {
        String showId = createShow("idem-show-1", List.of("A1", "A2"), 25000L, 4);
        ReserveSeatRequest req = new ReserveSeatRequest(List.of("A1"));

        // First call
        String firstRes = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .header("Idempotency-Key", "idempotent-token-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String reservationId1 = objectMapper.readTree(firstRes).get("reservation_id").asText();

        // Second identical call
        String secondRes = mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .header("Idempotency-Key", "idempotent-token-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String reservationId2 = objectMapper.readTree(secondRes).get("reservation_id").asText();

        assertThat(reservationId1).isEqualTo(reservationId2);

        // Verify only 1 seat was confirmed, NOT 2
        mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(1))
            .andExpect(jsonPath("$.confirmed").value(1));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Idempotency Case 2: Same key + different body returns 409 IDEMPOTENCY_KEY_REUSED")
    void testIdempotencyMismatchDifferentBody() throws Exception {
        String showId = createShow("idem-show-2", List.of("A1", "A2", "A3"), 25000L, 4);

        // First call reserving A1
        ReserveSeatRequest req1 = new ReserveSeatRequest(List.of("A1"));
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .header("Idempotency-Key", "reused-key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req1)))
            .andExpect(status().isCreated());

        // Second call with same key but requesting A2
        ReserveSeatRequest req2 = new ReserveSeatRequest(List.of("A2"));
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .header("Idempotency-Key", "reused-key-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req2)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"))
            .andExpect(jsonPath("$.message", containsString("different request payload")));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Should enforce per-user limit and reject excess requests with 409 PER_USER_LIMIT")
    void testPerUserLimitEnforcement() throws Exception {
        String showId = createShow("limited-show", List.of("A1", "A2", "A3", "A4"), 25000L, 2);

        // 1. Reserve 1 seat -> Success (1/2)
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReserveSeatRequest(List.of("A1")))))
            .andExpect(status().isCreated());

        // 2. Reserve 2 seats -> Rejection (1 + 2 = 3 > 2)
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReserveSeatRequest(List.of("A2", "A3")))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("PER_USER_LIMIT"))
            .andExpect(jsonPath("$.message", containsString("Exceeds per-user seat limit")));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Identity spoofing: Body field cannot override JWT identity")
    void testIdentitySpoofingAttempt() throws Exception {
        String showId = createShow("spoof-show", List.of("A1"), 25000L, 4);

        // Client sends custom body with user_id "user-B"
        String payload = "{\"user_id\":\"user-B\",\"seats\":[\"A1\"]}";

        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", user1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user_id").value("user-1"));
    }

    @Test
    @DisplayName("POST /shows/{id}/reserve - Unauthenticated request returns 401")
    void testUnauthenticatedReservation() throws Exception {
        mockMvc.perform(post("/shows/" + UUID.randomUUID() + "/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[\"A1\"]}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
