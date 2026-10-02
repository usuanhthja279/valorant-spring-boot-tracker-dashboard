package com.valorant.tracker.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
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
    when(entityManager.createQuery(contains("order by s.timestamp asc, s.id asc"), eq(StreamSample.class)))
        .thenReturn(query);
    when(query.setParameter(eq("from"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setParameter(eq("to"), any(OffsetDateTime.class))).thenReturn(query);
    when(query.setMaxResults(5000)).thenReturn(query);
    OffsetDateTime first = OffsetDateTime.parse("2026-10-02T09:00:00Z");
    OffsetDateTime last = OffsetDateTime.parse("2026-10-02T10:00:00Z");
    StreamSample earlier =
        new StreamSample(first, new LiveStream("Twitch", "s1", "channel-1", "Channel", "Title", 100, "url"));
    StreamSample later =
        new StreamSample(last, new LiveStream("Twitch", "s2", "channel-1", "Channel", "Title", 200, "url"));
    when(query.getResultList()).thenReturn(List.of(earlier, later));

    mockMvc
        .perform(
            get("/api/analytics")
                .param("from", "2026-10-02T08:00:00Z")
                .param("to", "2026-10-02T11:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.samplesReturned").value(2))
        .andExpect(jsonPath("$.analyticsComplete").value(true))
        .andExpect(jsonPath("$.growthRankingsComplete").value(true))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.overallPeakViewers").value(200))
        .andExpect(jsonPath("$.overallAverageViewers").value(150.0))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].firstViewers").value(100))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].lastViewers").value(200))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].growthViewers").value(100));
  }

  @Test
  void analyticsProcessesSamplesBeyondOnePageWithoutMarkingResultsTruncated() throws Exception {
    @SuppressWarnings("unchecked")
    TypedQuery<StreamSample> firstPageQuery = mock(TypedQuery.class);
    @SuppressWarnings("unchecked")
    TypedQuery<StreamSample> secondPageQuery = mock(TypedQuery.class);
    when(entityManager.createQuery(contains("order by s.timestamp asc, s.id asc"), eq(StreamSample.class)))
        .thenReturn(firstPageQuery);
    doReturn(secondPageQuery).when(entityManager)
        .createQuery(contains("cursorTimestamp"), eq(StreamSample.class));
    for (TypedQuery<StreamSample> query : List.of(firstPageQuery, secondPageQuery)) {
      when(query.setParameter(eq("from"), any(OffsetDateTime.class))).thenReturn(query);
      when(query.setParameter(eq("to"), any(OffsetDateTime.class))).thenReturn(query);
      when(query.setMaxResults(5000)).thenReturn(query);
    }
    when(secondPageQuery.setParameter(eq("cursorTimestamp"), any(OffsetDateTime.class)))
        .thenReturn(secondPageQuery);
    when(secondPageQuery.setParameter(eq("cursorId"), any())).thenReturn(secondPageQuery);

    OffsetDateTime start = OffsetDateTime.parse("2026-10-02T09:00:00Z");
    List<StreamSample> firstPage = new java.util.ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      firstPage.add(new StreamSample(start.plusSeconds(i),
          new LiveStream("Twitch", "stream-" + i, "channel-1", "Channel", "Title", 100, "url")));
    }
    StreamSample finalSample = new StreamSample(start.plusSeconds(5000),
        new LiveStream("Twitch", "stream-final", "channel-1", "Channel", "Title", 250, "url"));
    when(firstPageQuery.getResultList()).thenReturn(firstPage);
    when(secondPageQuery.getResultList()).thenReturn(List.of(finalSample));

    mockMvc.perform(get("/api/analytics")
            .param("from", "2026-10-02T08:00:00Z")
            .param("to", "2026-10-03T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.samplesReturned").value(5001))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].firstViewers").value(100))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].lastViewers").value(250))
        .andExpect(jsonPath("$.fastestGrowingChannels[0].growthViewers").value(150));
  }

}
