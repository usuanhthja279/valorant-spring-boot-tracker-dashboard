package com.valorant.tracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.valorant.tracker.model.LiveStream;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class TrackerServiceProviderFailureTest {
  @Test
  void fetchesAllProvidersConcurrentlyBeforePersistingSnapshot() {
    YouTubeScraperService youtube = mock(YouTubeScraperService.class);
    TwitchService twitch = mock(TwitchService.class);
    KickScraperService kick = mock(KickScraperService.class);
    EntityManager entityManager = mock(EntityManager.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    CountDownLatch providersStarted = new CountDownLatch(3);
    when(youtube.fetch()).thenAnswer(invocation -> {
      providersStarted.countDown();
      if (!providersStarted.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Providers were not fetched concurrently");
      }
      return List.of();
    });
    when(twitch.fetch()).thenAnswer(invocation -> {
      providersStarted.countDown();
      if (!providersStarted.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Providers were not fetched concurrently");
      }
      return List.of();
    });
    when(kick.fetch()).thenAnswer(invocation -> {
      providersStarted.countDown();
      if (!providersStarted.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Providers were not fetched concurrently");
      }
      return List.of();
    });

    TrackerService tracker = new TrackerService(youtube, twitch, kick, entityManager, txManager);
    tracker.collect();

    assertEquals("UP", tracker.getProviderHealth().get("YouTube").status());
    assertEquals("UP", tracker.getProviderHealth().get("Twitch").status());
    assertEquals("UP", tracker.getProviderHealth().get("Kick").status());
    verify(entityManager).persist(any(com.valorant.tracker.model.Snapshot.class));
  }

  @Test
  void rateLimitedProviderIsMarkedDownWithoutBlockingOtherProviders() {
    YouTubeScraperService youtube = mock(YouTubeScraperService.class);
    TwitchService twitch = mock(TwitchService.class);
    KickScraperService kick = mock(KickScraperService.class);
    EntityManager entityManager = mock(EntityManager.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
    when(youtube.fetch()).thenThrow(new WebClientResponseException(
        429, "Too Many Requests", HttpHeaders.EMPTY, new byte[0], null));
    when(twitch.fetch()).thenReturn(List.of());
    when(kick.fetch()).thenReturn(List.of());

    TrackerService tracker = new TrackerService(youtube, twitch, kick, entityManager, txManager);
    tracker.collect();

    assertEquals("DOWN", tracker.getProviderHealth().get("YouTube").status());
    assertEquals("UP", tracker.getProviderHealth().get("Twitch").status());
    assertEquals("UP", tracker.getProviderHealth().get("Kick").status());
    assertNotNull(tracker.getLastRun());
    verify(entityManager).persist(any(com.valorant.tracker.model.Snapshot.class));
  }

  @Test
  void successfulProviderIsMarkedUpWithCounts() {
    YouTubeScraperService youtube = mock(YouTubeScraperService.class);
    TwitchService twitch = mock(TwitchService.class);
    KickScraperService kick = mock(KickScraperService.class);
    EntityManager entityManager = mock(EntityManager.class);
    PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(mock(TransactionStatus.class));
    when(youtube.fetch()).thenReturn(List.of(new LiveStream(
        "YouTube", "stream-1", "channel-1", "Channel", "Live", 125, "https://example.test")));
    when(twitch.fetch()).thenReturn(List.of());
    when(kick.fetch()).thenReturn(List.of());

    TrackerService tracker = new TrackerService(youtube, twitch, kick, entityManager, txManager);
    tracker.collect();

    assertEquals("UP", tracker.getProviderHealth().get("YouTube").status());
    assertEquals(1, tracker.getProviderHealth().get("YouTube").streamCount());
    assertEquals(125L, tracker.getProviderHealth().get("YouTube").viewerCount());
  }
}
