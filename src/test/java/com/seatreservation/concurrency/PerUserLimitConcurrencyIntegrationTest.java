package com.seatreservation.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ReserveSeatRequest;
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

import java.util.ArrayList;
import java.util.List;
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
class PerUserLimitConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("Per-User Limit Race: 10 concurrent requests from 1 user for 10 distinct seats with limit=4 -> exactly 4 succeed and 6 rejected with 409")
    void testConcurrentPerUserLimitRace() throws Exception {
        String adminToken = "Bearer " + jwtService.generateToken("admin", "ADMIN");
        String userToken = "Bearer " + jwtService.generateToken("heavy-buyer", "USER");

        // Create show with 10 available seats and per_user_limit = 4
        List<String> seats = List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10");
        CreateShowRequest showRequest = new CreateShowRequest("rock-festival", seats, 25000L, 4);

        String createRes = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(showRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitConflictCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String seatNumber = seats.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to align
                    ReserveSeatRequest req = new ReserveSeatRequest(List.of(seatNumber));
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                            .header("Authorization", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                        .andReturn();

                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 201) {
                        successCount.incrementAndGet();
                    } else if (statusCode == 409) {
                        String body = result.getResponse().getContentAsString();
                        if (body.contains("PER_USER_LIMIT")) {
                            limitConflictCount.incrementAndGet();
                        } else {
                            unexpectedErrorCount.incrementAndGet();
                        }
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

        startLatch.countDown(); // Fire all 10 requests simultaneously
        boolean finished = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(unexpectedErrorCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(4);
        assertThat(limitConflictCount.get()).isEqualTo(6);

        // Verify show state reconciliation
        String getShowRes = mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        long confirmed = objectMapper.readTree(getShowRes).get("confirmed").asLong();
        long available = objectMapper.readTree(getShowRes).get("available").asLong();
        long total = objectMapper.readTree(getShowRes).get("total_seats").asLong();

        assertThat(confirmed).isEqualTo(4);
        assertThat(available).isEqualTo(6);
        assertThat(confirmed + available).isEqualTo(total);
    }
}
