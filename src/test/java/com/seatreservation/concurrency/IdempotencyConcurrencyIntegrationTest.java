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

import java.util.Collections;
import java.util.List;
import java.util.Set;
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
class IdempotencyConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("Idempotency Race: 50 concurrent identical requests with same key & body -> creates exactly 1 reservation, returns identical reservation_id")
    void testConcurrentIdempotentRequests() throws Exception {
        String adminToken = "Bearer " + jwtService.generateToken("admin", "ADMIN");
        String userToken = "Bearer " + jwtService.generateToken("idem-user", "USER");

        // Create show with 5 seats
        List<String> seats = List.of("A1", "A2", "A3", "A4", "A5");
        CreateShowRequest showRequest = new CreateShowRequest("idem-concurrent-show", seats, 25000L, 4);

        String createRes = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(showRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
        Set<String> returnedReservationIds = Collections.synchronizedSet(new java.util.HashSet<>());

        String sharedIdempotencyKey = "shared-uuid-key-999";
        ReserveSeatRequest request = new ReserveSeatRequest(List.of("A1"));

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/shows/" + showId + "/reserve")
                            .header("Authorization", userToken)
                            .header("Idempotency-Key", sharedIdempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                        .andReturn();

                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 201 || statusCode == 200) {
                        successCount.incrementAndGet();
                        String resId = objectMapper.readTree(result.getResponse().getContentAsString())
                            .get("reservation_id").asText();
                        returnedReservationIds.add(resId);
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
        boolean finished = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(unexpectedErrorCount.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(threadCount);
        // All successful requests must have received the exact same reservation ID
        assertThat(returnedReservationIds).hasSize(1);

        // Verify show state: exactly 1 seat was confirmed, 4 seats remain available
        String getShowRes = mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        long confirmed = objectMapper.readTree(getShowRes).get("confirmed").asLong();
        long available = objectMapper.readTree(getShowRes).get("available").asLong();
        long total = objectMapper.readTree(getShowRes).get("total_seats").asLong();

        assertThat(confirmed).isEqualTo(1);
        assertThat(available).isEqualTo(4);
        assertThat(confirmed + available).isEqualTo(total);
    }
}
