package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.RunningHrZone;
import it.giuseppefrattura.garminservice.service.RunHrZoneService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for running HR zone endpoints.
 */
@RestController
@RequestMapping("/api")
public class RunHrZoneController {

    private final RunHrZoneService service;
    private final int defaultDays;

    public RunHrZoneController(
            RunHrZoneService service,
            @Value("${garmin.hr-zones.default-days:10}") int defaultDays) {
        this.service = service;
        this.defaultDays = defaultDays;
    }

    /**
     * Returns aggregated HR zone minutes for running activities
     * in the last N days (default from config, overridable via query param).
     */
    @GetMapping("/run-hr-zones")
    public ResponseEntity<Map<String, Object>> runHrZones(
            @RequestParam(value = "days", required = false) Integer days) {
        return ResponseEntity.ok(service.getRunHrZones(days != null ? days : defaultDays));
    }

    /**
     * Fetches HR zones and persists them to PostgreSQL (upsert).
     * Returns the saved records.
     */
    @PostMapping("/run-hr-zones/persist")
    public ResponseEntity<Map<String, Object>> runHrZonesPersist(
            @RequestParam(value = "days", required = false) Integer days) {
        return ResponseEntity.ok(service.persistRunHrZones(days != null ? days : defaultDays));
    }

    /**
     * Returns all persisted running HR zones directly from the PostgreSQL database.
     */
    @GetMapping("/run-hr-zones/db")
    public ResponseEntity<List<RunningHrZone>> runHrZonesFromDb() {
        return ResponseEntity.ok(service.getRunHrZonesFromDb());
    }
}
