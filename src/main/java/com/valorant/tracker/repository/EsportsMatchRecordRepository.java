package com.valorant.tracker.repository;

import com.valorant.tracker.model.EsportsMatchRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EsportsMatchRecordRepository extends JpaRepository<EsportsMatchRecord, String> {
  List<EsportsMatchRecord> findByGameIgnoreCaseOrderByStartTimeDesc(String game);
}
