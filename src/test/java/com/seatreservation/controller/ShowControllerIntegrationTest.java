package com.seatreservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatreservation.dto.CreateShowRequest;
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
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES)
@ActiveProfiles("test")
class ShowControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /shows - Should create show successfully and return 201 Created")
    void testCreateShowSuccess() throws Exception {
        CreateShowRequest request = new CreateShowRequest(
            "friday-night",
            List.of("A1", "A2", "A3", "A4"),
            25000L,
            4
        );

        mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("X-Request-ID", "req-create-show-1"))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Request-ID", "req-create-show-1"))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.name").value("friday-night"))
            .andExpect(jsonPath("$.price_paise").value(25000))
            .andExpect(jsonPath("$.per_user_limit").value(4))
            .andExpect(jsonPath("$.total_seats").value(4))
            .andExpect(jsonPath("$.available").value(4))
            .andExpect(jsonPath("$.held").value(0))
            .andExpect(jsonPath("$.confirmed").value(0))
            .andExpect(jsonPath("$.seats", hasSize(4)))
            .andExpect(jsonPath("$.seats[0].seat_number").value("A1"))
            .andExpect(jsonPath("$.seats[0].status").value("available"));
    }

    @Test
    @DisplayName("POST /shows - Should reject duplicate seats with 400 Bad Request")
    void testCreateShowDuplicateSeats() throws Exception {
        CreateShowRequest request = new CreateShowRequest(
            "friday-night-dup",
            List.of("A1", "A2", "A1"),
            25000L,
            4
        );

        mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message", containsString("Duplicate seat identifier")))
            .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    @Test
    @DisplayName("POST /shows - Should reject invalid price and blank name with 400")
    void testCreateShowValidationFailures() throws Exception {
        // Blank name
        CreateShowRequest blankName = new CreateShowRequest(
            "   ",
            List.of("A1"),
            25000L,
            4
        );
        mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blankName)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // Invalid price <= 0
        CreateShowRequest invalidPrice = new CreateShowRequest(
            "Show-1",
            List.of("A1"),
            0L,
            4
        );
        mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPrice)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        // Empty seats
        CreateShowRequest emptySeats = new CreateShowRequest(
            "Show-1",
            List.of(),
            25000L,
            4
        );
        mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptySeats)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /shows/{id} - Should return show with reconciled seat counts")
    void testGetShowSuccess() throws Exception {
        CreateShowRequest createRequest = new CreateShowRequest(
            "saturday-gala",
            List.of("B1", "B2", "B3"),
            50000L,
            2
        );

        String createRes = mockMvc.perform(post("/shows")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        String showId = objectMapper.readTree(createRes).get("id").asText();

        mockMvc.perform(get("/shows/" + showId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(showId))
            .andExpect(jsonPath("$.name").value("saturday-gala"))
            .andExpect(jsonPath("$.price_paise").value(50000))
            .andExpect(jsonPath("$.total_seats").value(3))
            .andExpect(jsonPath("$.available").value(3))
            .andExpect(jsonPath("$.held").value(0))
            .andExpect(jsonPath("$.confirmed").value(0))
            .andExpect(jsonPath("$.seats", hasSize(3)));
    }

    @Test
    @DisplayName("GET /shows/{id} - Should return 404 for non-existent show")
    void testGetShowNotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/shows/" + nonExistentId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.message", containsString("Show not found with id: " + nonExistentId)))
            .andExpect(jsonPath("$.request_id").isNotEmpty());
    }
}
