package it.giuseppefrattura.garminservice.scheduler;

import it.giuseppefrattura.garminservice.service.RunHrZoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Background scheduler to automatically synchronize Garmin data once a day.
 */
@Component
public class GarminSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(GarminSyncScheduler.class);

    private final RunHrZoneService hrZoneService;
    private final int defaultDays;

    public GarminSyncScheduler(
            RunHrZoneService hrZoneService,
            @Value("${garmin.hr-zones.default-days:10}") int defaultDays) {
        this.hrZoneService = hrZoneService;
        this.defaultDays = defaultDays;
        log.info("GarminSyncScheduler initialized with default sync lookback: {} days", defaultDays);
    }

    /**
     * Daily sync scheduled for 3 AM.
     * Cron expression: "second minute hour day-of-month month day-of-week"
     * "0 0 3 * * *" -> at 03:00:00 every day.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void performDailySync() {
        log.info("Starting automated daily Garmin Connect data synchronization…");
        try {
            Map<String, Object> result = hrZoneService.persistRunHrZones(defaultDays);
            log.info("Automated daily Garmin synchronization completed successfully: {}", result.get("status"));
            if (result.containsKey("data")) {
                Map<?, ?> data = (Map<?, ?>) result.get("data");
                log.info("Sync summary -> activitiesCount: {}, savedCount: {}",
                        data.get("activitiesCount"), data.get("savedCount"));
            }
        } catch (Exception e) {
            log.error("Error occurred during automated daily Garmin synchronization: {}", e.getMessage(), e);
        }
    }
}
