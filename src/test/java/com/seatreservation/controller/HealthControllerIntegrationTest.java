package com.seatreservation.controller;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
class HealthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /health/live - Should return 200 UP without querying database")
    void testLivenessEndpoint() throws Exception {
        mockMvc.perform(get("/health/live"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /health/ready - Should return 200 UP when database is reachable")
    void testReadinessEndpointHealthy() throws Exception {
        mockMvc.perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    @DisplayName("GET /health/ready - Should return 503 SERVICE_UNAVAILABLE when database is unreachable")
    void testReadinessEndpointUnhealthy() {
        DataSource faultyDataSource = mock(DataSource.class);
        try {
            when(faultyDataSource.getConnection()).thenThrow(new SQLException("Connection refused"));
        } catch (SQLException ignored) {
        }

        HealthController healthController = new HealthController(faultyDataSource);
        var response = healthController.readiness();

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(503);
        org.assertj.core.api.Assertions.assertThat(response.getBody()).containsEntry("status", "DOWN");
        org.assertj.core.api.Assertions.assertThat(response.getBody()).containsEntry("database", "DOWN");
    }
}
