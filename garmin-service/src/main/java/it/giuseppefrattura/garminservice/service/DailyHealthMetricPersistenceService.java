package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Transactional persistence service for daily Garmin health metrics.
 * Separated from {@link GarminHealthSyncService} so each daily upsert runs in its own
 * Spring-proxied transaction without holding a database transaction open during slow HTTP calls.
 */
@Service
public class DailyHealthMetricPersistenceService {

    private final DailyHealthMetricRepository healthRepository;
    private final ReadinessCalculationService readinessService;

    public DailyHealthMetricPersistenceService(DailyHealthMetricRepository healthRepository,
                                               ReadinessCalculationService readinessService) {
        this.healthRepository = healthRepository;
        this.readinessService = readinessService;
    }

    @Transactional
    public DailyHealthMetric saveDailyHealthSummary(LocalDate date, Map<String, Object> summary) {
        DailyHealthMetric metric = healthRepository.findByMetricDate(date)
                .orElseGet(() -> {
                    DailyHealthMetric m = new DailyHealthMetric();
                    m.setMetricDate(date);
                    return m;
                });

        if (summary != null) {
            parseSleepData(metric, summary.get("sleep"));
            parseBodyBatteryData(metric, summary.get("body_battery"));
            parseHrvData(metric, summary.get("hrv"));
            parseStressData(metric, summary.get("stress"));
        }

        ReadinessCalculationService.ReadinessResult readiness = readinessService.calculateReadiness(metric);
        metric.setReadinessScore(readiness.getScore());
        metric.setReadinessLevel(readiness.getLevel());
        metric.setReadinessAdvice(readiness.getAdvice());

        return healthRepository.save(metric);
    }

    private void parseSleepData(DailyHealthMetric metric, Object sleepObj) {
        if (!(sleepObj instanceof Map<?, ?> sleepMap)) {
            return;
        }

        if (sleepMap.get("dailySleepDTO") instanceof Map<?, ?> dailySleep) {
            metric.setSleepDurationSeconds(getInteger(dailySleep, "sleepTimeSeconds"));
            metric.setDeepSleepSeconds(getInteger(dailySleep, "deepSleepSeconds"));
            metric.setLightSleepSeconds(getInteger(dailySleep, "lightSleepSeconds"));
            metric.setRemSleepSeconds(getInteger(dailySleep, "remSleepSeconds"));
            metric.setAwakeSeconds(getInteger(dailySleep, "awakeSleepSeconds"));

            if (dailySleep.get("sleepScores") instanceof Map<?, ?> scoresMap
                    && scoresMap.get("overall") instanceof Map<?, ?> overallMap) {
                metric.setSleepScore(getInteger(overallMap, "value"));
                metric.setSleepQualifier(getString(overallMap, "qualifierKey"));
            }
        }

        Integer rhr = getInteger(sleepMap, "restingHeartRate");
        if (rhr != null) {
            metric.setRestingHeartRate(rhr);
        }
        Integer bbChange = getInteger(sleepMap, "bodyBatteryChange");
        if (bbChange != null) {
            metric.setBodyBatteryCharged(bbChange);
        }
    }

    private void parseBodyBatteryData(DailyHealthMetric metric, Object bbObj) {
        if (!(bbObj instanceof List<?> bbList) || bbList.isEmpty()) {
            return;
        }
        if (!(bbList.get(0) instanceof Map<?, ?> bbMap)) {
            return;
        }

        Integer charged = getInteger(bbMap, "charged");
        if (charged != null) {
            metric.setBodyBatteryCharged(charged);
        }
        Integer drained = getInteger(bbMap, "drained");
        if (drained != null) {
            metric.setBodyBatteryDrained(drained);
        }

        if (bbMap.get("bodyBatteryValuesArray") instanceof List<?> valList && !valList.isEmpty()) {
            applyBodyBatterySamples(metric, valList);
        }
    }

    private void applyBodyBatterySamples(DailyHealthMetric metric, List<?> valList) {
        int min = 100;
        int max = 0;
        Integer wake = null;

        for (Object item : valList) {
            Integer v = extractBodyBatterySample(item);
            if (v != null) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                if (wake == null) {
                    wake = v;
                }
            }
        }
        if (min <= 100) {
            metric.setBodyBatteryMin(min);
        }
        if (max > 0) {
            metric.setBodyBatteryMax(max);
        }
        if (wake != null) {
            metric.setBodyBatteryWake(wake);
        }
    }

    private Integer extractBodyBatterySample(Object item) {
        if (item instanceof List<?> pair && pair.size() >= 2 && pair.get(1) instanceof Number num) {
            return num.intValue();
        }
        return null;
    }

    private void parseHrvData(DailyHealthMetric metric, Object hrvObj) {
        if (!(hrvObj instanceof Map<?, ?> hrvMap)) {
            return;
        }

        if (hrvMap.get("hrvSummary") instanceof Map<?, ?> summary) {
            metric.setHrvNightlyAvg(getDouble(summary, "lastNightAvg"));
            metric.setHrvWeeklyAvg(getDouble(summary, "weeklyAvg"));
            metric.setHrvStatus(getString(summary, "status"));

            if (summary.get("baseline") instanceof Map<?, ?> baseline) {
                metric.setHrvBaselineLow(getDouble(baseline, "lowUpper"));
                metric.setHrvBaselineBalancedLow(getDouble(baseline, "balancedLow"));
                metric.setHrvBaselineBalancedUpper(getDouble(baseline, "balancedUpper"));
            }
        }
    }

    private void parseStressData(DailyHealthMetric metric, Object stressObj) {
        if (!(stressObj instanceof Map<?, ?> stressMap)) {
            return;
        }

        metric.setAvgStressLevel(getInteger(stressMap, "avgStressLevel"));
        metric.setMaxStressLevel(getInteger(stressMap, "maxStressLevel"));
        metric.setStressRestDurationSeconds(getInteger(stressMap, "restStressDuration"));
        metric.setStressLowDurationSeconds(getInteger(stressMap, "lowStressDuration"));
        metric.setStressMediumDurationSeconds(getInteger(stressMap, "mediumStressDuration"));
        metric.setStressHighDurationSeconds(getInteger(stressMap, "highStressDuration"));
    }

    private Integer getInteger(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof Number num ? num.intValue() : null;
    }

    private Double getDouble(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof Number num ? num.doubleValue() : null;
    }

    private String getString(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}

