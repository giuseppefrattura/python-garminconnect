package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_health_metrics", indexes = {
    @Index(name = "idx_health_metric_date", columnList = "metric_date")
})
public class DailyHealthMetric {

    @Id
    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    // Sleep Metrics
    @Column(name = "sleep_score")
    private Integer sleepScore;

    @Column(name = "sleep_qualifier", length = 50)
    private String sleepQualifier; // EXCELLENT, GOOD, FAIR, POOR

    @Column(name = "sleep_duration_seconds")
    private Integer sleepDurationSeconds;

    @Column(name = "deep_sleep_seconds")
    private Integer deepSleepSeconds;

    @Column(name = "light_sleep_seconds")
    private Integer lightSleepSeconds;

    @Column(name = "rem_sleep_seconds")
    private Integer remSleepSeconds;

    @Column(name = "awake_seconds")
    private Integer awakeSeconds;

    @Column(name = "resting_heart_rate")
    private Integer restingHeartRate;

    // Body Battery Metrics
    @Column(name = "body_battery_wake")
    private Integer bodyBatteryWake;

    @Column(name = "body_battery_max")
    private Integer bodyBatteryMax;

    @Column(name = "body_battery_min")
    private Integer bodyBatteryMin;

    @Column(name = "body_battery_charged")
    private Integer bodyBatteryCharged;

    @Column(name = "body_battery_drained")
    private Integer bodyBatteryDrained;

    // HRV (Heart Rate Variability) Metrics
    @Column(name = "hrv_nightly_avg")
    private Double hrvNightlyAvg;

    @Column(name = "hrv_status", length = 30)
    private String hrvStatus; // BALANCED, LOW, UNBALANCED, POOR

    @Column(name = "hrv_weekly_avg")
    private Double hrvWeeklyAvg;

    @Column(name = "hrv_baseline_low")
    private Double hrvBaselineLow;

    @Column(name = "hrv_baseline_balanced_low")
    private Double hrvBaselineBalancedLow;

    @Column(name = "hrv_baseline_balanced_upper")
    private Double hrvBaselineBalancedUpper;

    // Stress Metrics
    @Column(name = "avg_stress_level")
    private Integer avgStressLevel;

    @Column(name = "max_stress_level")
    private Integer maxStressLevel;

    @Column(name = "stress_rest_duration_seconds")
    private Integer stressRestDurationSeconds;

    @Column(name = "stress_low_duration_seconds")
    private Integer stressLowDurationSeconds;

    @Column(name = "stress_medium_duration_seconds")
    private Integer stressMediumDurationSeconds;

    @Column(name = "stress_high_duration_seconds")
    private Integer stressHighDurationSeconds;

    // Readiness & Recovery
    @Column(name = "readiness_score")
    private Integer readinessScore; // 0 - 100

    @Column(name = "readiness_level", length = 30)
    private String readinessLevel; // OPTIMAL, MODERATE, FATIGUED

    @Column(name = "readiness_advice", length = 500)
    private String readinessAdvice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public DailyHealthMetric() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public DailyHealthMetric(LocalDate metricDate) {
        this.metricDate = metricDate;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    public void onPrePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public LocalDate getMetricDate() {
        return metricDate;
    }

    public void setMetricDate(LocalDate metricDate) {
        this.metricDate = metricDate;
    }

    public Integer getSleepScore() {
        return sleepScore;
    }

    public void setSleepScore(Integer sleepScore) {
        this.sleepScore = sleepScore;
    }

    public String getSleepQualifier() {
        return sleepQualifier;
    }

    public void setSleepQualifier(String sleepQualifier) {
        this.sleepQualifier = sleepQualifier;
    }

    public Integer getSleepDurationSeconds() {
        return sleepDurationSeconds;
    }

    public void setSleepDurationSeconds(Integer sleepDurationSeconds) {
        this.sleepDurationSeconds = sleepDurationSeconds;
    }

    public Integer getDeepSleepSeconds() {
        return deepSleepSeconds;
    }

    public void setDeepSleepSeconds(Integer deepSleepSeconds) {
        this.deepSleepSeconds = deepSleepSeconds;
    }

    public Integer getLightSleepSeconds() {
        return lightSleepSeconds;
    }

    public void setLightSleepSeconds(Integer lightSleepSeconds) {
        this.lightSleepSeconds = lightSleepSeconds;
    }

    public Integer getRemSleepSeconds() {
        return remSleepSeconds;
    }

    public void setRemSleepSeconds(Integer remSleepSeconds) {
        this.remSleepSeconds = remSleepSeconds;
    }

    public Integer getAwakeSeconds() {
        return awakeSeconds;
    }

    public void setAwakeSeconds(Integer awakeSeconds) {
        this.awakeSeconds = awakeSeconds;
    }

    public Integer getRestingHeartRate() {
        return restingHeartRate;
    }

    public void setRestingHeartRate(Integer restingHeartRate) {
        this.restingHeartRate = restingHeartRate;
    }

    public Integer getBodyBatteryWake() {
        return bodyBatteryWake;
    }

    public void setBodyBatteryWake(Integer bodyBatteryWake) {
        this.bodyBatteryWake = bodyBatteryWake;
    }

    public Integer getBodyBatteryMax() {
        return bodyBatteryMax;
    }

    public void setBodyBatteryMax(Integer bodyBatteryMax) {
        this.bodyBatteryMax = bodyBatteryMax;
    }

    public Integer getBodyBatteryMin() {
        return bodyBatteryMin;
    }

    public void setBodyBatteryMin(Integer bodyBatteryMin) {
        this.bodyBatteryMin = bodyBatteryMin;
    }

    public Integer getBodyBatteryCharged() {
        return bodyBatteryCharged;
    }

    public void setBodyBatteryCharged(Integer bodyBatteryCharged) {
        this.bodyBatteryCharged = bodyBatteryCharged;
    }

    public Integer getBodyBatteryDrained() {
        return bodyBatteryDrained;
    }

    public void setBodyBatteryDrained(Integer bodyBatteryDrained) {
        this.bodyBatteryDrained = bodyBatteryDrained;
    }

    public Double getHrvNightlyAvg() {
        return hrvNightlyAvg;
    }

    public void setHrvNightlyAvg(Double hrvNightlyAvg) {
        this.hrvNightlyAvg = hrvNightlyAvg;
    }

    public String getHrvStatus() {
        return hrvStatus;
    }

    public void setHrvStatus(String hrvStatus) {
        this.hrvStatus = hrvStatus;
    }

    public Double getHrvWeeklyAvg() {
        return hrvWeeklyAvg;
    }

    public void setHrvWeeklyAvg(Double hrvWeeklyAvg) {
        this.hrvWeeklyAvg = hrvWeeklyAvg;
    }

    public Double getHrvBaselineLow() {
        return hrvBaselineLow;
    }

    public void setHrvBaselineLow(Double hrvBaselineLow) {
        this.hrvBaselineLow = hrvBaselineLow;
    }

    public Double getHrvBaselineBalancedLow() {
        return hrvBaselineBalancedLow;
    }

    public void setHrvBaselineBalancedLow(Double hrvBaselineBalancedLow) {
        this.hrvBaselineBalancedLow = hrvBaselineBalancedLow;
    }

    public Double getHrvBaselineBalancedUpper() {
        return hrvBaselineBalancedUpper;
    }

    public void setHrvBaselineBalancedUpper(Double hrvBaselineBalancedUpper) {
        this.hrvBaselineBalancedUpper = hrvBaselineBalancedUpper;
    }

    public Integer getAvgStressLevel() {
        return avgStressLevel;
    }

    public void setAvgStressLevel(Integer avgStressLevel) {
        this.avgStressLevel = avgStressLevel;
    }

    public Integer getMaxStressLevel() {
        return maxStressLevel;
    }

    public void setMaxStressLevel(Integer maxStressLevel) {
        this.maxStressLevel = maxStressLevel;
    }

    public Integer getStressRestDurationSeconds() {
        return stressRestDurationSeconds;
    }

    public void setStressRestDurationSeconds(Integer stressRestDurationSeconds) {
        this.stressRestDurationSeconds = stressRestDurationSeconds;
    }

    public Integer getStressLowDurationSeconds() {
        return stressLowDurationSeconds;
    }

    public void setStressLowDurationSeconds(Integer stressLowDurationSeconds) {
        this.stressLowDurationSeconds = stressLowDurationSeconds;
    }

    public Integer getStressMediumDurationSeconds() {
        return stressMediumDurationSeconds;
    }

    public void setStressMediumDurationSeconds(Integer stressMediumDurationSeconds) {
        this.stressMediumDurationSeconds = stressMediumDurationSeconds;
    }

    public Integer getStressHighDurationSeconds() {
        return stressHighDurationSeconds;
    }

    public void setStressHighDurationSeconds(Integer stressHighDurationSeconds) {
        this.stressHighDurationSeconds = stressHighDurationSeconds;
    }

    public Integer getReadinessScore() {
        return readinessScore;
    }

    public void setReadinessScore(Integer readinessScore) {
        this.readinessScore = readinessScore;
    }

    public String getReadinessLevel() {
        return readinessLevel;
    }

    public void setReadinessLevel(String readinessLevel) {
        this.readinessLevel = readinessLevel;
    }

    public String getReadinessAdvice() {
        return readinessAdvice;
    }

    public void setReadinessAdvice(String readinessAdvice) {
        this.readinessAdvice = readinessAdvice;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
