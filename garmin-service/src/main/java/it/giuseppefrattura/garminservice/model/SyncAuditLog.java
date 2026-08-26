package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sync_audit_logs")
public class SyncAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_type", nullable = false, length = 50)
    private String syncType;

    @Column(name = "triggered_by", nullable = false, length = 50)
    private String triggeredBy;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "garmin_workouts_count")
    private Integer garminWorkoutsCount = 0;

    @Column(name = "garmin_health_days")
    private Integer garminHealthDays = 0;

    @Column(name = "renpho_measurements_count")
    private Integer renphoMeasurementsCount = 0;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public SyncAuditLog() {
    }

    public SyncAuditLog(String syncType, String triggeredBy, OffsetDateTime startedAt, String status) {
        this.syncType = syncType;
        this.triggeredBy = triggeredBy;
        this.startedAt = startedAt;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getSyncType() {
        return syncType;
    }

    public void setSyncType(String syncType) {
        this.syncType = syncType;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getGarminWorkoutsCount() {
        return garminWorkoutsCount;
    }

    public void setGarminWorkoutsCount(Integer garminWorkoutsCount) {
        this.garminWorkoutsCount = garminWorkoutsCount;
    }

    public Integer getGarminHealthDays() {
        return garminHealthDays;
    }

    public void setGarminHealthDays(Integer garminHealthDays) {
        this.garminHealthDays = garminHealthDays;
    }

    public Integer getRenphoMeasurementsCount() {
        return renphoMeasurementsCount;
    }

    public void setRenphoMeasurementsCount(Integer renphoMeasurementsCount) {
        this.renphoMeasurementsCount = renphoMeasurementsCount;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
