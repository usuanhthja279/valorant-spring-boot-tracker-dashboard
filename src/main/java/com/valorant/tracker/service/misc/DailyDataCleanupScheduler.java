package com.valorant.tracker.service.misc;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes the previous day's inactive hours from the time-series history. */
@Service
public class DailyDataCleanupScheduler {
  private static final Logger log = LoggerFactory.getLogger(DailyDataCleanupScheduler.class);
  private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  @PersistenceContext private EntityManager entityManager;

  @Scheduled(cron = "0 30 21 * * *", zone = "Asia/Kolkata")
  @Transactional
  public void deleteDailyInactiveWindow() {
    LocalDate today = LocalDate.now(ZONE);
    OffsetDateTime from = today.minusDays(1).atTime(21, 0).atZone(ZONE).toOffsetDateTime();
    OffsetDateTime to = today.atTime(13, 0).atZone(ZONE).toOffsetDateTime();

    int samplesDeleted = entityManager.createQuery(
        "delete from StreamSample sample where sample.timestamp >= :from and sample.timestamp < :to")
        .setParameter("from", from)
        .setParameter("to", to)
        .executeUpdate();
    int snapshotsDeleted = entityManager.createQuery(
        "delete from Snapshot snapshot where snapshot.timestamp >= :from and snapshot.timestamp < :to")
        .setParameter("from", from)
        .setParameter("to", to)
        .executeUpdate();

    log.info("Daily data cleanup removed {} stream samples and {} snapshots between {} and {}",
        samplesDeleted, snapshotsDeleted, from, to);
  }
}
