package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import it.giuseppefrattura.garminservice.service.GarminHealthSyncService;
import it.giuseppefrattura.garminservice.service.ReadinessCalculationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/health")
public class GarminHealthController {

    private final DailyHealthMetricRepository healthRepository;
    private final GarminHealthSyncService healthSyncService;
    private final ReadinessCalculationService readinessService;

    public GarminHealthController(DailyHealthMetricRepository healthRepository,
                                  GarminHealthSyncService healthSyncService,
                                  ReadinessCalculationService readinessService) {
        this.healthRepository = healthRepository;
        this.healthSyncService = healthSyncService;
        this.readinessService = readinessService;
    }

    /**
     * Get today's or latest readiness score, status, and coaching advice.
     */
    @GetMapping("/today-readiness")
    public ResponseEntity<Map<String, Object>> getTodayReadiness() {
        LocalDate today = LocalDate.now();
        Optional<DailyHealthMetric> opt = healthRepository.findByMetricDate(today);

        if (opt.isEmpty()) {
            // Check top most recent
            opt = healthRepository.findTopByOrderByMetricDateDesc();
        }

        DailyHealthMetric metric = opt.orElse(null);
        ReadinessCalculationService.ReadinessResult readiness = readinessService.calculateReadiness(metric);

        Map<String, Object> data = new HashMap<>();
        data.put("metricDate", metric != null ? metric.getMetricDate() : today);
        data.put("readinessScore", readiness.getScore());
        data.put("readinessLevel", readiness.getLevel());
        data.put("readinessAdvice", readiness.getAdvice());

        if (metric != null) {
            data.put("sleepScore", metric.getSleepScore());
            data.put("sleepDurationSeconds", metric.getSleepDurationSeconds());
            data.put("deepSleepSeconds", metric.getDeepSleepSeconds());
            data.put("remSleepSeconds", metric.getRemSleepSeconds());
            data.put("lightSleepSeconds", metric.getLightSleepSeconds());
            data.put("awakeSeconds", metric.getAwakeSeconds());
            data.put("restingHeartRate", metric.getRestingHeartRate());
            data.put("bodyBatteryWake", metric.getBodyBatteryWake());
            data.put("bodyBatteryMax", metric.getBodyBatteryMax());
            data.put("bodyBatteryMin", metric.getBodyBatteryMin());
            data.put("hrvNightlyAvg", metric.getHrvNightlyAvg());
            data.put("hrvStatus", metric.getHrvStatus());
            data.put("hrvWeeklyAvg", metric.getHrvWeeklyAvg());
            data.put("avgStressLevel", metric.getAvgStressLevel());
            data.put("maxStressLevel", metric.getMaxStressLevel());
        }

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "data", data
        ));
    }

    /**
     * Get health metric for a specific date.
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyHealth(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Optional<DailyHealthMetric> metric = healthRepository.findByMetricDate(date);
        return metric.map(m -> ResponseEntity.ok(Map.of("status", "success", "data", (Object) m)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("status", "not_found", "message", "No health data for " + date)));
    }

    /**
     * Get past N days of health metrics in chronological order for trend charts.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHealthHistory(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(1, Math.min(days, 90)));

        List<DailyHealthMetric> list = healthRepository.findByMetricDateBetweenOrderByMetricDateAsc(start, end);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "totalDays", list.size(),
                "data", list
        ));
    }

    /**
     * Trigger manual sync of health metrics from Garmin Connect for the past N days.
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncHealth(
            @RequestParam(value = "days", defaultValue = "14") int days) {
        Map<String, Object> result = healthSyncService.syncRecentHealthMetrics(days);
        return ResponseEntity.ok(result);
    }
}
