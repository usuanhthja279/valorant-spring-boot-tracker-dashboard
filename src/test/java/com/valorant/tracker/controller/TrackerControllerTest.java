package com.valorant.tracker.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.service.TrackerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TrackerControllerTest {
  @Mock EntityManager entityManager;
  @Mock TrackerService tracker;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new TrackerController(entityManager, tracker)).build();
  }

  @Test
  void snapshotsRangeQueriesRequestedTimeWindowAndCapsLimit() throws Exception {
    @SuppressWarnings("unchecked")
    TypedQuery<Snapshot> query = mock(TypedQuery.class);
    when(entityManager.createQuery(contains("s.timestamp >= :from"), eq(Snapshot.class)))
        .thenReturn(query);
    when(query.setParameter(eq("from"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setParameter(eq("to"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setMaxResults(20000)).thenReturn(query);
    when(query.getResultList()).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/snapshots/range")
                .param("from", "2026-10-02T09:00:00+05:30")
                .param("to", "2026-10-02T10:00:00+05:30")
                .param("limit", "25000"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));

    verify(query).setMaxResults(20000);
  }

  @Test
  void snapshotsRangeRejectsReversedTimeWindowAsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/api/snapshots/range")
                .param("from", "2026-10-02T10:00:00Z")
                .param("to", "2026-10-02T09:00:00Z"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void channelHistoryRequiresBothRangeParameters() throws Exception {
    mockMvc
        .perform(
            get("/api/channels/history")
                .param("name", "channel")
                .param("from", "2026-10-02T09:00:00Z"))
        .andExpect(status().isBadRequest());
  }
}
