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
class MultiSeatConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    @DisplayName("Multi-Seat Overlapping Race: Concurrent requests with cyclic/overlapping seat pairs execute without deadlock and maintain all-or-nothing atomicity")
    void testOverlappingMultiSeatConcurrentRace() throws Exception {
        String adminToken = "Bearer " + jwtService.generateToken("admin", "ADMIN");

        // Create show with 4 seats: A1, A2, A3, A4
        List<String> seats = List.of("A1", "A2", "A3", "A4");
        CreateShowRequest showRequest = new CreateShowRequest("multi-seat-show", seats, 25000L, 4);

        String createRes = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(showRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        // 4 overlapping combinations that would cause deadlocks if not sorted deterministically
        List<List<String>> candidateRequests = List.of(
            List.of("A2", "A1"), // Reverse input order tests sorting
            List.of("A2", "A3"),
            List.of("A4", "A3"),
            List.of("A1", "A4")
        );

        int totalRequests = 40; // 10 workers per candidate pair
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        AtomicInteger serverError5xxCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            final List<String> requestedSeats = candidateRequests.get(i % candidateRequests.size());
            final String userToken = "Bearer " + jwtService.generateToken("multi-user-" + index, "USER");

            executor.submit(() -> {
                try {
                    startLatch.await();
                    ReserveSeatRequest req = new ReserveSeatRequest(requestedSeats);
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                            .header("Authorization", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                        .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    } else if (status >= 500) {
                        serverError5xxCount.incrementAndGet();
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

        startLatch.countDown();
        boolean completed = endLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(serverError5xxCount.get()).isEqualTo(0);
        assertThat(unexpectedErrorCount.get()).isEqualTo(0);
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(totalRequests);

        // Verify final show reconciliation invariant: available + confirmed == 4
        String getShowRes = mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        long confirmed = objectMapper.readTree(getShowRes).get("confirmed").asLong();
        long available = objectMapper.readTree(getShowRes).get("available").asLong();
        long total = objectMapper.readTree(getShowRes).get("total_seats").asLong();

        assertThat(confirmed + available).isEqualTo(total);
        assertThat(total).isEqualTo(4);
    }
}
