package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Service
public class GarminHealthSyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminHealthSyncService.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GarminProxyClient proxyClient;
    private final DailyHealthMetricPersistenceService persistenceService;

    @Autowired
    public GarminHealthSyncService(GarminProxyClient proxyClient,
                                   DailyHealthMetricPersistenceService persistenceService) {
        this.proxyClient = proxyClient;
        this.persistenceService = persistenceService;
    }

    public GarminHealthSyncService(GarminProxyClient proxyClient,
                                   DailyHealthMetricRepository healthRepository,
                                   ReadinessCalculationService readinessService) {
        this(proxyClient, new DailyHealthMetricPersistenceService(healthRepository, readinessService));
    }

    /**
     * Synchronize metrics for the last N days.
     */
    public Map<String, Object> syncRecentHealthMetrics(int days) {
        LocalDate today = LocalDate.now();
        int synced = 0;
        int errors = 0;

        for (int i = 0; i < days; i++) {
            LocalDate targetDate = today.minusDays(i);
            try {
                syncHealthMetricForDate(targetDate);
                synced++;
            } catch (Exception e) {
                log.warn("Failed to sync health metrics for {}: {}", targetDate, e.getMessage());
                errors++;
            }
        }

        return Map.of(
                "status", "success",
                "syncedDays", synced,
                "errorDays", errors,
                "requestedDays", days
        );
    }

    /**
     * Synchronize a specific date by fetching from garmin-proxy outside the DB transaction
     * and persisting atomically via {@link DailyHealthMetricPersistenceService}.
     */
    public DailyHealthMetric syncHealthMetricForDate(LocalDate date) {
        String dateStr = date.format(ISO_DATE);
        log.info("Syncing health metrics from Garmin for date: {}", dateStr);

        Map<String, Object> summary = proxyClient.getDailyHealthSummary(dateStr);
        return persistenceService.saveDailyHealthSummary(date, summary);
    }
}
