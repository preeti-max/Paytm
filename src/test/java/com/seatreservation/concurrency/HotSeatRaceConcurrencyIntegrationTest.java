package com.seatreservation.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ReserveSeatRequest;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.security.JwtService;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
class HotSeatRaceConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    @DisplayName("Hot-Seat Storm: 100 concurrent distinct users reserve seat A12 -> Exactly 1 winner (201), 99 declines (409 SEAT_TAKEN), 0 errors (5xx)")
    void test100UsersRaceForSingleSeat() throws Exception {
        String adminToken = "Bearer " + jwtService.generateToken("admin", "ADMIN");

        // 1. Create show with 10 total seats including hot-seat A12
        List<String> seats = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A12");
        CreateShowRequest showRequest = new CreateShowRequest("rock-concert-hot-seat", seats, 25000L, 4);

        String createRes = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(showRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        // 2. Prepare 100 distinct users with pre-generated JWT tokens
        int userCount = 100;
        String[] userTokens = new String[userCount];
        for (int i = 0; i < userCount; i++) {
            userTokens[i] = "Bearer " + jwtService.generateToken("contestant-user-" + i, "USER");
        }

        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(userCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger seatTakenCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        AtomicInteger unexpected5xxCount = new AtomicInteger(0);

        ReserveSeatRequest request = new ReserveSeatRequest(List.of("A12"));
        String payload = objectMapper.writeValueAsString(request);

        // 3. Launch 100 concurrent requests against seat A12
        for (int i = 0; i < userCount; i++) {
            final String token = userTokens[i];
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize starting moment
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                        .andReturn();

                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 201) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 409) {
                        String body = result.getResponse().getContentAsString();
                        if (body.contains("SEAT_TAKEN")) {
                            seatTakenCount.incrementAndGet();
                        } else {
                            unexpectedErrorCount.incrementAndGet();
                        }
                    } else if (statusCode >= 500) {
                        unexpected5xxCount.incrementAndGet();
                    } else {
                        unexpectedErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpectedErrorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Unleash storm
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 4. Assert Concurrency Correctness
        assertThat(completed).isTrue();
        assertThat(unexpected5xxCount.get()).isEqualTo(0);
        assertThat(unexpectedErrorCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(seatTakenCount.get()).isEqualTo(99);

        // 5. Assert Database Integrity & Reconciliation
        var showSeats = seatRepository.findByShowIdOrderBySeatNumberAsc(UUID.fromString(showId));
        var a12Seat = showSeats.stream().filter(s -> s.getSeatNumber().equals("A12")).findFirst().get();
        assertThat(a12Seat.getStatus()).isEqualTo(SeatStatus.CONFIRMED);
        assertThat(a12Seat.getHeldBy()).startsWith("contestant-user-");

        String getShowRes = mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        long confirmed = objectMapper.readTree(getShowRes).get("confirmed").asLong();
        long available = objectMapper.readTree(getShowRes).get("available").asLong();
        long held = objectMapper.readTree(getShowRes).get("held").asLong();
        long total = objectMapper.readTree(getShowRes).get("total_seats").asLong();

        assertThat(confirmed).isEqualTo(1);
        assertThat(held).isEqualTo(0);
        assertThat(available).isEqualTo(9);
        assertThat(available + held + confirmed).isEqualTo(total);
    }
}
