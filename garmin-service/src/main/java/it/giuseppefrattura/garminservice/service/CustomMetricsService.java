package it.giuseppefrattura.garminservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service managing custom Micrometer metrics for Grafana Cloud observation.
 * Exposes Sync KPIs, Fitness & Biometrics metrics, and Security metrics.
 */
@Service
public class CustomMetricsService {

    private final MeterRegistry meterRegistry;

    // Sync KPIs
    private final AtomicReference<Double> syncStatus = new AtomicReference<>(1.0); // 1.0=SUCCESS, 0.5=PARTIAL, 0.0=FAIL
    private final AtomicLong lastSyncTimestamp = new AtomicLong(0L);
    private final AtomicReference<Double> lastSyncDurationSeconds = new AtomicReference<>(0.0);
    private final AtomicInteger syncWorkoutsCount = new AtomicInteger(0);
    private final AtomicInteger syncHealthDaysCount = new AtomicInteger(0);
    private final AtomicInteger syncRenphoMeasurementsCount = new AtomicInteger(0);

    // Fitness & Biometrics
    private final AtomicReference<Double> readinessScore = new AtomicReference<>(0.0);
    private final AtomicReference<Double> sleepScore = new AtomicReference<>(0.0);
    private final AtomicLong sleepDurationSeconds = new AtomicLong(0L);
    private final AtomicReference<Double> hrvNightlyAvg = new AtomicReference<>(0.0);
    private final AtomicReference<Double> stressAvg = new AtomicReference<>(0.0);
    private final AtomicReference<Double> bodyWeightKg = new AtomicReference<>(0.0);
    private final AtomicReference<Double> bodyFatPct = new AtomicReference<>(0.0);
    private final AtomicReference<Double> muscleMassKg = new AtomicReference<>(0.0);

    // Workout & PRs
    private final AtomicReference<Double> latestWorkoutVolumeKg = new AtomicReference<>(0.0);
    private final AtomicInteger latestWorkoutSetsCount = new AtomicInteger(0);
    private final AtomicInteger totalPrsCount = new AtomicInteger(0);

    // Security Counters
    private final ConcurrentMap<String, Counter> rateLimitCounters = new ConcurrentHashMap<>();
    private final Counter loginFailureCounter;

    public CustomMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Register Sync Gauges
        Gauge.builder("garmin_sync_status", syncStatus, ref -> ref.get())
                .description("Status of the latest unified synchronization (1.0=SUCCESS, 0.5=PARTIAL, 0.0=FAILED)")
                .register(meterRegistry);

        Gauge.builder("garmin_last_sync_timestamp_seconds", lastSyncTimestamp, ref -> (double) ref.get())
                .description("Epoch timestamp in seconds of the latest unified synchronization")
                .register(meterRegistry);

        Gauge.builder("garmin_last_sync_duration_seconds", lastSyncDurationSeconds, ref -> ref.get())
                .description("Duration in seconds of the latest unified synchronization")
                .register(meterRegistry);

        Gauge.builder("garmin_sync_workouts_count", syncWorkoutsCount, ref -> (double) ref.get())
                .description("Number of strength workouts processed during the latest sync")
                .register(meterRegistry);

        Gauge.builder("garmin_sync_health_days_count", syncHealthDaysCount, ref -> (double) ref.get())
                .description("Number of daily health records processed during the latest sync")
                .register(meterRegistry);

        Gauge.builder("renpho_sync_measurements_count", syncRenphoMeasurementsCount, ref -> (double) ref.get())
                .description("Number of Renpho scale measurements processed during the latest sync")
                .register(meterRegistry);

        // Register Fitness Gauges
        Gauge.builder("fitness_readiness_score", readinessScore, ref -> ref.get())
                .description("Latest calculated Training Readiness score (0-100)")
                .register(meterRegistry);

        Gauge.builder("fitness_sleep_score", sleepScore, ref -> ref.get())
                .description("Latest recorded sleep quality score (0-100)")
                .register(meterRegistry);

        Gauge.builder("fitness_sleep_duration_seconds", sleepDurationSeconds, ref -> (double) ref.get())
                .description("Latest recorded total sleep duration in seconds")
                .register(meterRegistry);

        Gauge.builder("fitness_hrv_nightly_avg", hrvNightlyAvg, ref -> ref.get())
                .description("Latest nightly average Heart Rate Variability in ms")
                .register(meterRegistry);

        Gauge.builder("fitness_stress_avg", stressAvg, ref -> ref.get())
                .description("Latest average daily stress level (0-100)")
                .register(meterRegistry);

        Gauge.builder("fitness_body_weight_kg", bodyWeightKg, ref -> ref.get())
                .description("Latest recorded body weight in kg from Renpho scale")
                .register(meterRegistry);

        Gauge.builder("fitness_body_fat_pct", bodyFatPct, ref -> ref.get())
                .description("Latest recorded body fat percentage from Renpho scale")
                .register(meterRegistry);

        Gauge.builder("fitness_muscle_mass_kg", muscleMassKg, ref -> ref.get())
                .description("Latest recorded muscle mass in kg from Renpho scale")
                .register(meterRegistry);

        // Register Workout Gauges
        Gauge.builder("fitness_latest_workout_volume_kg", latestWorkoutVolumeKg, ref -> ref.get())
                .description("Total lifted volume in kg for the most recent strength workout")
                .register(meterRegistry);

        Gauge.builder("fitness_latest_workout_sets_count", latestWorkoutSetsCount, ref -> (double) ref.get())
                .description("Total number of sets in the most recent strength workout")
                .register(meterRegistry);

        Gauge.builder("fitness_total_prs_count", totalPrsCount, ref -> (double) ref.get())
                .description("Total number of historical Personal Records recorded")
                .register(meterRegistry);

        // Security Counter
        this.loginFailureCounter = Counter.builder("security_login_failures_total")
                .description("Total number of failed dashboard login attempts")
                .register(meterRegistry);
    }

    public void recordSyncResult(String status, double durationSeconds, int workouts, int healthDays, int renphoMeasurements) {
        double statusVal = 0.0;
        if ("SUCCESS".equalsIgnoreCase(status)) {
            statusVal = 1.0;
        } else if ("PARTIAL".equalsIgnoreCase(status)) {
            statusVal = 0.5;
        }
        this.syncStatus.set(statusVal);
        this.lastSyncTimestamp.set(System.currentTimeMillis() / 1000L);
        this.lastSyncDurationSeconds.set(durationSeconds);
        this.syncWorkoutsCount.set(workouts);
        this.syncHealthDaysCount.set(healthDays);
        this.syncRenphoMeasurementsCount.set(renphoMeasurements);
    }

    public void recordReadinessScore(double score) {
        this.readinessScore.set(score);
    }

    public void recordSleepMetrics(double score, long durationSeconds) {
        this.sleepScore.set(score);
        this.sleepDurationSeconds.set(durationSeconds);
    }

    public void recordHrvAndStress(double hrv, double stress) {
        this.hrvNightlyAvg.set(hrv);
        this.stressAvg.set(stress);
    }

    public void recordBodyComposition(double weightKg, double fatPct, double muscleKg) {
        this.bodyWeightKg.set(weightKg);
        this.bodyFatPct.set(fatPct);
        this.muscleMassKg.set(muscleKg);
    }

    public void recordWorkoutMetrics(double volumeKg, int sets, int totalPrs) {
        this.latestWorkoutVolumeKg.set(volumeKg);
        this.latestWorkoutSetsCount.set(sets);
        this.totalPrsCount.set(totalPrs);
    }

    public void incrementRateLimitHits(String category) {
        rateLimitCounters.computeIfAbsent(category, cat ->
                Counter.builder("security_rate_limit_hits_total")
                        .tag("category", cat)
                        .description("Total requests blocked by rate limiting")
                        .register(meterRegistry)
        ).increment();
    }

    public void incrementLoginFailure() {
        loginFailureCounter.increment();
    }

    // Getters for testing
    public double getSyncStatus() { return syncStatus.get(); }
    public long getLastSyncTimestamp() { return lastSyncTimestamp.get(); }
    public double getLastSyncDurationSeconds() { return lastSyncDurationSeconds.get(); }
    public int getSyncWorkoutsCount() { return syncWorkoutsCount.get(); }
    public int getSyncHealthDaysCount() { return syncHealthDaysCount.get(); }
    public int getSyncRenphoMeasurementsCount() { return syncRenphoMeasurementsCount.get(); }
    public double getReadinessScore() { return readinessScore.get(); }
    public double getBodyWeightKg() { return bodyWeightKg.get(); }
}
