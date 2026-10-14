package com.valorant.tracker.repository;

import com.valorant.tracker.model.VlrLiveMapTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VlrLiveMapTrackRepository extends JpaRepository<VlrLiveMapTrack, String> {
    List<VlrLiveMapTrack> findByStremngMatchIdOrderByCreatedAtDesc(String stremngMatchId);
    List<VlrLiveMapTrack> findByEnabledTrue();
}
