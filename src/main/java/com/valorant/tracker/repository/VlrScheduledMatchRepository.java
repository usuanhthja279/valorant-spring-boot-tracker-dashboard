package com.valorant.tracker.repository;

import com.valorant.tracker.model.VlrScheduledMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface VlrScheduledMatchRepository extends JpaRepository<VlrScheduledMatch, String> {
    List<VlrScheduledMatch> findByMatchDate(LocalDate matchDate);
    List<VlrScheduledMatch> findByPollingStatusAndNextAttemptAtLessThanEqual(String pollingStatus, java.time.Instant now);
    List<VlrScheduledMatch> findByStremngMatchId(String stremngMatchId);
}
