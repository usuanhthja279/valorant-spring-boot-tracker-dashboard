package com.valorant.tracker.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.valorant.tracker.service.tracker.TrackerService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrackerControllerRangeTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(
        new TrackerController(mock(EntityManager.class), mock(TrackerService.class))).build();
  }

  @Test
  void reversedAnalyticsRangeReturnsBadRequest() throws Exception {
    mvc.perform(get("/api/analytics")
            .param("from", "2026-10-02T12:00:00Z")
            .param("to", "2026-10-02T11:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingAnalyticsRangeParameterReturnsBadRequest() throws Exception {
    mvc.perform(get("/api/analytics").param("from", "2026-10-02T12:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reversedSnapshotRangeReturnsBadRequest() throws Exception {
    mvc.perform(get("/api/snapshots/range")
            .param("from", "2026-10-02T12:00:00Z")
            .param("to", "2026-10-02T11:00:00Z"))
        .andExpect(status().isBadRequest());
  }
}
