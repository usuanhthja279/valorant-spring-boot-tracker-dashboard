package com.valorant.tracker.controller;

import com.valorant.tracker.service.tracker.TrackerService;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final EntityManager entityManager;
    private final TrackerService trackerService;

    public HealthController(EntityManager entityManager, TrackerService trackerService) {
        this.entityManager = entityManager;
        this.trackerService = trackerService;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("database", "UP");
        try {
            entityManager.createQuery("select count(s) from Snapshot s", Long.class).getSingleResult();
        } catch (RuntimeException exception) {
            result.put("status", "DOWN");
            result.put("database", "DOWN");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Database health check failed", exception);
        }
        result.put("lastRun", trackerService.getLastRun() == null ? "not-run" : trackerService.getLastRun());
        result.put("providers", trackerService.getProviderHealth());
        return result;
    }
}
