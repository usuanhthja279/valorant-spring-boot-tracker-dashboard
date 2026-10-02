package com.valorant.tracker.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
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

  @Test
  void analyticsCalculatesMetricsFromTheMostRecentWindowSamples() throws Exception {
    @SuppressWarnings("unchecked")
    TypedQuery<StreamSample> query = mock(TypedQuery.class);
    when(entityManager.createQuery(contains("order by s.timestamp desc"), eq(StreamSample.class)))
        .thenReturn(query);
    when(query.setParameter(eq("from"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setParameter(eq("to"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setMaxResults(20001)).thenReturn(query);
    OffsetDateTime first = OffsetDateTime.parse("2026-10-02T09:00:00Z");
    OffsetDateTime last = OffsetDateTime.parse("2026-10-02T10:00:00Z");
    StreamSample earlier =
        new StreamSample(first, new LiveStream("Twitch", "s1", "channel-1", "Channel", "Title", 100, "url"));
    StreamSample later =
        new StreamSample(last, new LiveStream("Twitch", "s2", "channel-1", "Channel", "Title", 200, "url"));
    when(query.getResultList()).thenReturn(List.of(later, earlier));

    mockMvc
        .perform(
            get("/api/analytics")
                .param("from", "2026-10-02T08:00:00Z")
                .param("to", "2026-10-02T11:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.samplesReturned").value(2))
        .andExpect(jsonPath("$.overallPeakViewers").value(200))
        .andExpect(jsonPath("$.overallAverageViewers").value(150.0))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].firstViewers").value(100))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].lastViewers").value(200))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].growthViewers").value(100));
  }
}
