package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class GarminHealthSyncService {

    private static final Logger log = LoggerFactory.getLogger(GarminHealthSyncService.class);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GarminProxyClient proxyClient;
    private final DailyHealthMetricRepository healthRepository;
    private final ReadinessCalculationService readinessService;

    public GarminHealthSyncService(GarminProxyClient proxyClient,
                                   DailyHealthMetricRepository healthRepository,
                                   ReadinessCalculationService readinessService) {
        this.proxyClient = proxyClient;
        this.healthRepository = healthRepository;
        this.readinessService = readinessService;
    }

    /**
     * Sync health metrics for the past N days.
     */
    @Transactional
    public Map<String, Object> syncRecentHealthMetrics(int days) {
        int count = Math.max(1, Math.min(days, 60));
        LocalDate today = LocalDate.now();
        int syncedDays = 0;

        for (int i = 0; i < count; i++) {
            LocalDate date = today.minusDays(i);
            try {
                syncHealthMetricForDate(date);
                syncedDays++;
            } catch (Exception e) {
                log.warn("Failed to sync health metrics for date {}: {}", date, e.getMessage());
            }
        }

        return Map.of(
                "status", "success",
                "syncedDays", syncedDays,
                "requestedDays", count
        );
    }

    /**
     * Sync health metrics for a specific date.
     */
    @Transactional
    public DailyHealthMetric syncHealthMetricForDate(LocalDate date) {
        String dateStr = date.format(ISO_DATE);
        log.info("Syncing health metrics from Garmin for date: {}", dateStr);

        Map<String, Object> summary = proxyClient.getDailyHealthSummary(dateStr);
        DailyHealthMetric metric = healthRepository.findByMetricDate(date)
                .orElse(new DailyHealthMetric(date));

        parseSleepData(metric, summary.get("sleep"));
        parseBodyBatteryData(metric, summary.get("body_battery"));
        parseHrvData(metric, summary.get("hrv"));
        parseStressData(metric, summary.get("stress"));

        // Calculate and attach readiness score
        ReadinessCalculationService.ReadinessResult readiness = readinessService.calculateReadiness(metric);
        metric.setReadinessScore(readiness.getScore());
        metric.setReadinessLevel(readiness.getLevel());
        metric.setReadinessAdvice(readiness.getAdvice());

        return healthRepository.save(metric);
    }

    @SuppressWarnings("unchecked")
    private void parseSleepData(DailyHealthMetric metric, Object sleepObj) {
        if (!(sleepObj instanceof Map<?, ?> sleepMap)) return;

        Object dailySleepDTOObj = sleepMap.get("dailySleepDTO");
        if (dailySleepDTOObj instanceof Map<?, ?> dailySleep) {
            metric.setSleepDurationSeconds(getInteger(dailySleep, "sleepTimeSeconds"));
            metric.setDeepSleepSeconds(getInteger(dailySleep, "deepSleepSeconds"));
            metric.setLightSleepSeconds(getInteger(dailySleep, "lightSleepSeconds"));
            metric.setRemSleepSeconds(getInteger(dailySleep, "remSleepSeconds"));
            metric.setAwakeSeconds(getInteger(dailySleep, "awakeSleepSeconds"));

            Object sleepScores = dailySleep.get("sleepScores");
            if (sleepScores instanceof Map<?, ?> scoresMap) {
                Object overall = scoresMap.get("overall");
                if (overall instanceof Map<?, ?> overallMap) {
                    metric.setSleepScore(getInteger(overallMap, "value"));
                    metric.setSleepQualifier(getString(overallMap, "qualifierKey"));
                }
            }
        }

        if (sleepMap.get("restingHeartRate") != null) {
            metric.setRestingHeartRate(getInteger(sleepMap, "restingHeartRate"));
        }
        if (sleepMap.get("bodyBatteryChange") != null) {
            metric.setBodyBatteryCharged(getInteger(sleepMap, "bodyBatteryChange"));
        }
    }

    @SuppressWarnings("unchecked")
    private void parseBodyBatteryData(DailyHealthMetric metric, Object bbObj) {
        if (bbObj instanceof List<?> bbList && !bbList.isEmpty()) {
            Object first = bbList.get(0);
            if (first instanceof Map<?, ?> bbMap) {
                if (bbMap.get("charged") != null) metric.setBodyBatteryCharged(getInteger(bbMap, "charged"));
                if (bbMap.get("drained") != null) metric.setBodyBatteryDrained(getInteger(bbMap, "drained"));

                Object valuesObj = bbMap.get("bodyBatteryValuesArray");
                if (valuesObj instanceof List<?> valList && !valList.isEmpty()) {
                    int min = 100;
                    int max = 0;
                    Integer wake = null;

                    for (Object item : valList) {
                        if (item instanceof List<?> pair && pair.size() >= 2) {
                            Object val = pair.get(1);
                            if (val instanceof Number num) {
                                int v = num.intValue();
                                if (v < min) min = v;
                                if (v > max) max = v;
                                if (wake == null) wake = v; // First recorded value of the day
                            }
                        }
                    }
                    if (min <= 100) metric.setBodyBatteryMin(min);
                    if (max > 0) metric.setBodyBatteryMax(max);
                    if (wake != null) metric.setBodyBatteryWake(wake);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseHrvData(DailyHealthMetric metric, Object hrvObj) {
        if (!(hrvObj instanceof Map<?, ?> hrvMap)) return;

        Object summaryObj = hrvMap.get("hrvSummary");
        if (summaryObj instanceof Map<?, ?> summary) {
            metric.setHrvNightlyAvg(getDouble(summary, "lastNightAvg"));
            metric.setHrvWeeklyAvg(getDouble(summary, "weeklyAvg"));
            metric.setHrvStatus(getString(summary, "status"));

            Object baselineObj = summary.get("baseline");
            if (baselineObj instanceof Map<?, ?> baseline) {
                metric.setHrvBaselineLow(getDouble(baseline, "lowUpper"));
                metric.setHrvBaselineBalancedLow(getDouble(baseline, "balancedLow"));
                metric.setHrvBaselineBalancedUpper(getDouble(baseline, "balancedUpper"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void parseStressData(DailyHealthMetric metric, Object stressObj) {
        if (!(stressObj instanceof Map<?, ?> stressMap)) return;

        metric.setAvgStressLevel(getInteger(stressMap, "avgStressLevel"));
        metric.setMaxStressLevel(getInteger(stressMap, "maxStressLevel"));
        metric.setStressRestDurationSeconds(getInteger(stressMap, "restStressDuration"));
        metric.setStressLowDurationSeconds(getInteger(stressMap, "lowStressDuration"));
        metric.setStressMediumDurationSeconds(getInteger(stressMap, "mediumStressDuration"));
        metric.setStressHighDurationSeconds(getInteger(stressMap, "highStressDuration"));
    }

    private Integer getInteger(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    private Double getDouble(Map<?, ?> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number num) {
            return num.doubleValue();
        }
        return null;
    }

    private String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
