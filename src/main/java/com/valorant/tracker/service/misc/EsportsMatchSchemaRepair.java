package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;

/**
 * Repairs the esports match table when an existing H2 database was created by
 * an older build. This is intentionally idempotent so restarting the tracker is
 * enough to bring the table in line with EsportsMatchRecord.
 */
@Component
public class EsportsMatchSchemaRepair {
    private static final Logger log = LoggerFactory.getLogger(EsportsMatchSchemaRepair.class);

    private final JdbcTemplate jdbcTemplate;

    public EsportsMatchSchemaRepair(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void repair() {
        try {
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS best_of VARCHAR(20)");
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS finished BOOLEAN DEFAULT FALSE NOT NULL");
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS team1score INTEGER");
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS team2score INTEGER");
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS winner VARCHAR(300)");
            jdbcTemplate.execute("ALTER TABLE esports_matches ADD COLUMN IF NOT EXISTS end_time TIMESTAMP WITH TIME ZONE");
            log.info("Esports match schema verified/repaired");
        } catch (Exception e) {
            // Hibernate ddl-auto=update remains the normal schema owner. This
            // repair is a safety net for an already-created database.
            log.warn("Could not repair esports_matches schema automatically: {}", e.getMessage());
        }
    }
}
