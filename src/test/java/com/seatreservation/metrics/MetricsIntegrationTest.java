package com.seatreservation.metrics;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
class MetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("GET /actuator/prometheus - Should expose reservation metrics and real seats_available gauge")
    void testPrometheusMetricsExposure() throws Exception {
        String adminToken = "Bearer " + jwtService.generateToken("admin", "ADMIN");
        String userToken = "Bearer " + jwtService.generateToken("metric-user", "USER");

        // 1. Create show with 2 seats
        CreateShowRequest showRequest = new CreateShowRequest("metric-show", List.of("M1", "M2"), 25000L, 4);
        String createRes = mockMvc.perform(post("/shows")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(showRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        // 2. Reserve M1
        ReserveSeatRequest req = new ReserveSeatRequest(List.of("M1"));
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        // 3. Attempt duplicate reservation on M1 (triggers seat_taken decline)
        mockMvc.perform(post("/shows/" + showId + "/reserve")
                .header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict());

        // 4. Query /actuator/prometheus
        String metricsOutput = mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(metricsOutput).contains("reservations_confirmed_total");
        assertThat(metricsOutput).contains("reservations_declined_total");
        assertThat(metricsOutput).contains("seats_available");
    }
}
