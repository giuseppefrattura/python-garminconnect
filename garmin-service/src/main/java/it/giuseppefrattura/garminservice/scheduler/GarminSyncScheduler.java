package it.giuseppefrattura.garminservice.scheduler;

import it.giuseppefrattura.garminservice.model.SyncAuditLog;
import it.giuseppefrattura.garminservice.repository.SyncAuditLogRepository;
import it.giuseppefrattura.garminservice.service.GarminHealthSyncService;
import it.giuseppefrattura.garminservice.service.RunHrZoneService;
import it.giuseppefrattura.garminservice.service.StrengthWorkoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Background scheduler to automatically synchronize all Garmin and Renpho data at midnight (00:00:00).
 */
@Component
public class GarminSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncScheduler.class);

    private final RunHrZoneService hrZoneService;
    private final StrengthWorkoutService strengthWorkoutService;
    private final GarminHealthSyncService healthSyncService;
    private final SyncAuditLogRepository auditLogRepository;
    private final RestTemplate restTemplate;
    private final it.giuseppefrattura.garminservice.service.CustomMetricsService customMetricsService;

    @Value("${garmin.renpho.url:http://renpho-service:8082}")
    private String renphoServiceUrl = "http://renpho-service:8082";

    @Value("${garmin.hr-zones.default-days:14}")
    private int defaultDays;

    public GarminSyncScheduler(
            RunHrZoneService hrZoneService,
            StrengthWorkoutService strengthWorkoutService,
            GarminHealthSyncService healthSyncService,
            SyncAuditLogRepository auditLogRepository,
            it.giuseppefrattura.garminservice.service.CustomMetricsService customMetricsService) {
        this.hrZoneService = hrZoneService;
        this.strengthWorkoutService = strengthWorkoutService;
        this.healthSyncService = healthSyncService;
        this.auditLogRepository = auditLogRepository;
        this.customMetricsService = customMetricsService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
        log.info("GarminSyncScheduler initialized (midnight cron: '0 0 0 * * *')");
    }

    GarminSyncScheduler(
            RunHrZoneService hrZoneService,
            StrengthWorkoutService strengthWorkoutService,
            GarminHealthSyncService healthSyncService,
            SyncAuditLogRepository auditLogRepository,
            RestTemplate restTemplate) {
        this(hrZoneService, strengthWorkoutService, healthSyncService, auditLogRepository, restTemplate, null);
    }

    GarminSyncScheduler(
            RunHrZoneService hrZoneService,
            StrengthWorkoutService strengthWorkoutService,
            GarminHealthSyncService healthSyncService,
            SyncAuditLogRepository auditLogRepository,
            RestTemplate restTemplate,
            it.giuseppefrattura.garminservice.service.CustomMetricsService customMetricsService) {
        this.hrZoneService = hrZoneService;
        this.strengthWorkoutService = strengthWorkoutService;
        this.healthSyncService = healthSyncService;
        this.auditLogRepository = auditLogRepository;
        this.restTemplate = restTemplate;
        this.customMetricsService = customMetricsService;
    }

    /**
     * Automated midnight sync scheduled for 00:00:00 every night.
     */
    @Scheduled(cron = "${garmin.sync.cron:0 0 0 * * *}")
    public void scheduledMidnightSync() {
        log.info("🌙 [MIDNIGHT SYNC] Triggering scheduled automated midnight synchronization for Garmin & Renpho...");
        performMidnightSync("SCHEDULER", "MIDNIGHT_AUTOMATED");
    }

    /**
     * Executes the complete unified sync pipeline across Garmin and Renpho.
     */
    public SyncAuditLog performMidnightSync(String triggeredBy, String syncType) {
        OffsetDateTime startTime = OffsetDateTime.now();
        SyncAuditLog auditLog = new SyncAuditLog(syncType, triggeredBy, startTime, "IN_PROGRESS");
        auditLogRepository.save(auditLog);

        int totalGarminWorkouts = 0;
        int totalGarminHealthDays = 0;
        int totalRenphoMeasurements = 0;
        StringBuilder detailsBuilder = new StringBuilder();
        boolean hasError = false;

        // 1. Sync Garmin Strength Workouts
        try {
            log.info("[MIDNIGHT SYNC] 1/4 Syncing Garmin strength workouts...");
            totalGarminWorkouts = strengthWorkoutService.syncStrengthWorkouts(20);
            detailsBuilder.append("Garmin Workouts: ").append(totalGarminWorkouts).append(" synced. ");
        } catch (Exception e) {
            log.error("[MIDNIGHT SYNC] Error syncing Garmin strength workouts: {}", e.getMessage());
            detailsBuilder.append("Garmin Workouts Error: ").append(e.getMessage()).append(". ");
            hasError = true;
        }

        // 2. Sync Garmin Run & HR Zones
        try {
            log.info("[MIDNIGHT SYNC] 2/4 Syncing Garmin run HR zones...");
            Map<String, Object> hrResult = hrZoneService.persistRunHrZones(defaultDays);
            detailsBuilder.append("HR Zones: ").append(hrResult.get("status")).append(". ");
        } catch (Exception e) {
            log.error("[MIDNIGHT SYNC] Error syncing Garmin HR zones: {}", e.getMessage());
            detailsBuilder.append("HR Zones Error: ").append(e.getMessage()).append(". ");
            hasError = true;
        }

        // 3. Sync Garmin Daily Health Biometrics (Steps, Sleep, Stress, HRV, SPO2)
        try {
            log.info("[MIDNIGHT SYNC] 3/4 Syncing Garmin daily health metrics...");
            Map<String, Object> healthResult = healthSyncService.syncRecentHealthMetrics(7);
            if (healthResult.get("syncedDays") instanceof Number num) {
                totalGarminHealthDays = num.intValue();
            }
            detailsBuilder.append("Garmin Health: ").append(totalGarminHealthDays).append(" days. ");
        } catch (Exception e) {
            log.error("[MIDNIGHT SYNC] Error syncing Garmin health metrics: {}", e.getMessage());
            detailsBuilder.append("Garmin Health Error: ").append(e.getMessage()).append(". ");
            hasError = true;
        }

        // 4. Sync Renpho Scale Weigh-ins via REST Call to renpho-service
        try {
            log.info("[MIDNIGHT SYNC] 4/4 Syncing Renpho scale data from {}...", renphoServiceUrl);
            String renphoSyncEndpoint = renphoServiceUrl + "/api/renpho/sync";
            ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<>() {};
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    renphoSyncEndpoint, Objects.requireNonNull(HttpMethod.POST), HttpEntity.EMPTY, typeRef);
            Map<String, Object> body = response.getBody();
            if (response.getStatusCode().is2xxSuccessful() && body != null) {
                if (body.get("syncedCount") instanceof Number num) {
                    totalRenphoMeasurements = num.intValue();
                }
                detailsBuilder.append("Renpho: ").append(totalRenphoMeasurements).append(" weigh-ins. ");
            }
        } catch (Exception e) {
            log.warn("[MIDNIGHT SYNC] Renpho service sync warning (service may be offline or locked): {}", e.getMessage());
            detailsBuilder.append("Renpho Sync Note: ").append(e.getMessage()).append(". ");
        }

        // Finalize Audit Log
        auditLog.setCompletedAt(OffsetDateTime.now());
        auditLog.setStatus(hasError ? "PARTIAL" : "SUCCESS");
        auditLog.setGarminWorkoutsCount(totalGarminWorkouts);
        auditLog.setGarminHealthDays(totalGarminHealthDays);
        auditLog.setRenphoMeasurementsCount(totalRenphoMeasurements);
        auditLog.setDetails(detailsBuilder.toString());
        
        SyncAuditLog savedLog = auditLogRepository.save(auditLog);
        log.info("🌙 [MIDNIGHT SYNC] Unified sync completed. Status: {}, Details: {}", savedLog.getStatus(), savedLog.getDetails());

        if (customMetricsService != null) {
            double durationSec = java.time.Duration.between(startTime, OffsetDateTime.now()).toMillis() / 1000.0;
            customMetricsService.recordSyncResult(
                    savedLog.getStatus(),
                    durationSec,
                    totalGarminWorkouts,
                    totalGarminHealthDays,
                    totalRenphoMeasurements
            );
        }

        return savedLog;
    }
}
