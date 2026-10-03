package com.valorant.tracker.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.valorant.tracker.service.TrackerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrackerHealthTest {
  private EntityManager entityManager;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    entityManager = mock(EntityManager.class);
    mvc = MockMvcBuilders.standaloneSetup(
        new TrackerController(entityManager, mock(TrackerService.class))).build();
  }

  @Test
  void healthReportsUpWhenDatabaseCanBeQueried() throws Exception {
    @SuppressWarnings("unchecked")
    TypedQuery<Long> query = mock(TypedQuery.class);
    when(entityManager.createQuery("select count(s) from Snapshot s", Long.class)).thenReturn(query);
    when(query.getSingleResult()).thenReturn(0L);

    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.database").value("UP"));
  }

  @Test
  void healthReturnsServiceUnavailableWhenDatabaseQueryFails() throws Exception {
    when(entityManager.createQuery("select count(s) from Snapshot s", Long.class))
        .thenThrow(new IllegalStateException("database unavailable"));

    mvc.perform(get("/api/health"))
        .andExpect(status().isServiceUnavailable());
  }
}
