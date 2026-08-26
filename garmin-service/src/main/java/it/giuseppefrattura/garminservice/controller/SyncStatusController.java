package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.SyncAuditLog;
import it.giuseppefrattura.garminservice.repository.SyncAuditLogRepository;
import it.giuseppefrattura.garminservice.scheduler.GarminSyncScheduler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sync")
public class SyncStatusController {

    private final SyncAuditLogRepository auditLogRepository;
    private final GarminSyncScheduler syncScheduler;

    public SyncStatusController(SyncAuditLogRepository auditLogRepository, GarminSyncScheduler syncScheduler) {
        this.auditLogRepository = auditLogRepository;
        this.syncScheduler = syncScheduler;
    }

    /**
     * Get the latest midnight auto-sync status and metrics.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        Optional<SyncAuditLog> latestOpt = auditLogRepository.findFirstByOrderByStartedAtDesc();
        Map<String, Object> response = new HashMap<>();

        if (latestOpt.isPresent()) {
            SyncAuditLog log = latestOpt.get();
            response.put("hasRun", true);
            response.put("syncType", log.getSyncType());
            response.put("triggeredBy", log.getTriggeredBy());
            response.put("startedAt", log.getStartedAt());
            response.put("completedAt", log.getCompletedAt());
            response.put("status", log.getStatus());
            response.put("garminWorkoutsCount", log.getGarminWorkoutsCount());
            response.put("garminHealthDays", log.getGarminHealthDays());
            response.put("renphoMeasurementsCount", log.getRenphoMeasurementsCount());
            response.put("details", log.getDetails());
        } else {
            response.put("hasRun", false);
            response.put("message", "Nessuna sincronizzazione automatica registrata al momento.");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Trigger manual execution of the midnight sync pipeline.
     */
    @PostMapping("/trigger-all")
    public ResponseEntity<SyncAuditLog> triggerAllSync() {
        SyncAuditLog result = syncScheduler.performMidnightSync("ADMIN_MANUAL", "MANUAL_TRIGGER");
        return ResponseEntity.ok(result);
    }
}
