package it.giuseppefrattura.garminservice.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomMetricsServiceTest {

    private MeterRegistry meterRegistry;
    private CustomMetricsService metricsService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metricsService = new CustomMetricsService(meterRegistry);
    }

    @Test
    void testRecordSyncResult_Success() {
        metricsService.recordSyncResult("SUCCESS", 15.2, 5, 7, 3);

        assertEquals(1.0, metricsService.getSyncStatus());
        assertEquals(15.2, metricsService.getLastSyncDurationSeconds());
        assertEquals(5, metricsService.getSyncWorkoutsCount());
        assertEquals(7, metricsService.getSyncHealthDaysCount());
        assertEquals(3, metricsService.getSyncRenphoMeasurementsCount());
        assertTrue(metricsService.getLastSyncTimestamp() > 0);

        // Verify Micrometer gauge reflects the value
        assertEquals(1.0, meterRegistry.get("garmin_sync_status").gauge().value());
        assertEquals(15.2, meterRegistry.get("garmin_last_sync_duration_seconds").gauge().value());
    }

    @Test
    void testRecordSyncResult_PartialAndFail() {
        metricsService.recordSyncResult("PARTIAL", 8.0, 2, 5, 0);
        assertEquals(0.5, metricsService.getSyncStatus());
        assertEquals(0.5, meterRegistry.get("garmin_sync_status").gauge().value());

        metricsService.recordSyncResult("FAIL", 4.0, 0, 0, 0);
        assertEquals(0.0, metricsService.getSyncStatus());
        assertEquals(0.0, meterRegistry.get("garmin_sync_status").gauge().value());
    }

    @Test
    void testRecordFitnessMetrics() {
        metricsService.recordReadinessScore(85.0);
        metricsService.recordSleepMetrics(90.0, 28800L);
        metricsService.recordHrvAndStress(65.0, 22.0);
        metricsService.recordBodyComposition(74.5, 14.2, 35.8);
        metricsService.recordWorkoutMetrics(12500.0, 18, 4);

        assertEquals(85.0, metricsService.getReadinessScore());
        assertEquals(74.5, metricsService.getBodyWeightKg());

        assertEquals(85.0, meterRegistry.get("fitness_readiness_score").gauge().value());
        assertEquals(90.0, meterRegistry.get("fitness_sleep_score").gauge().value());
        assertEquals(28800.0, meterRegistry.get("fitness_sleep_duration_seconds").gauge().value());
        assertEquals(65.0, meterRegistry.get("fitness_hrv_nightly_avg").gauge().value());
        assertEquals(22.0, meterRegistry.get("fitness_stress_avg").gauge().value());
        assertEquals(74.5, meterRegistry.get("fitness_body_weight_kg").gauge().value());
        assertEquals(14.2, meterRegistry.get("fitness_body_fat_pct").gauge().value());
        assertEquals(35.8, meterRegistry.get("fitness_muscle_mass_kg").gauge().value());
        assertEquals(12500.0, meterRegistry.get("fitness_latest_workout_volume_kg").gauge().value());
        assertEquals(18.0, meterRegistry.get("fitness_latest_workout_sets_count").gauge().value());
        assertEquals(4.0, meterRegistry.get("fitness_total_prs_count").gauge().value());
    }

    @Test
    void testSecurityCounters() {
        metricsService.incrementRateLimitHits("login");
        metricsService.incrementRateLimitHits("login");
        metricsService.incrementRateLimitHits("sync");
        metricsService.incrementLoginFailure();

        assertEquals(2.0, meterRegistry.get("security_rate_limit_hits_total").tag("category", "login").counter().count());
        assertEquals(1.0, meterRegistry.get("security_rate_limit_hits_total").tag("category", "sync").counter().count());
        assertEquals(1.0, meterRegistry.get("security_login_failures_total").counter().count());
    }
}
