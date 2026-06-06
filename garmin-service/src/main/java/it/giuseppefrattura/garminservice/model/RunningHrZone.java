package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code running_hr_zones} PostgreSQL table.
 */
@Entity
@Table(name = "running_hr_zones")
public class RunningHrZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "activity_id", nullable = false, unique = true)
    private Long activityId;

    @Column(name = "activity_name", length = 255)
    private String activityName;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "run_time", nullable = false)
    private LocalTime runTime;

    @Column(name = "zone_1_mins", precision = 5, scale = 2)
    private BigDecimal zone1Mins = BigDecimal.ZERO;

    @Column(name = "zone_2_mins", precision = 5, scale = 2)
    private BigDecimal zone2Mins = BigDecimal.ZERO;

    @Column(name = "zone_3_mins", precision = 5, scale = 2)
    private BigDecimal zone3Mins = BigDecimal.ZERO;

    @Column(name = "zone_4_mins", precision = 5, scale = 2)
    private BigDecimal zone4Mins = BigDecimal.ZERO;

    @Column(name = "zone_5_mins", precision = 5, scale = 2)
    private BigDecimal zone5Mins = BigDecimal.ZERO;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // --- Getters and Setters ---

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }

    public LocalDate getRunDate() { return runDate; }
    public void setRunDate(LocalDate runDate) { this.runDate = runDate; }

    public LocalTime getRunTime() { return runTime; }
    public void setRunTime(LocalTime runTime) { this.runTime = runTime; }

    public BigDecimal getZone1Mins() { return zone1Mins; }
    public void setZone1Mins(BigDecimal zone1Mins) { this.zone1Mins = zone1Mins; }

    public BigDecimal getZone2Mins() { return zone2Mins; }
    public void setZone2Mins(BigDecimal zone2Mins) { this.zone2Mins = zone2Mins; }

    public BigDecimal getZone3Mins() { return zone3Mins; }
    public void setZone3Mins(BigDecimal zone3Mins) { this.zone3Mins = zone3Mins; }

    public BigDecimal getZone4Mins() { return zone4Mins; }
    public void setZone4Mins(BigDecimal zone4Mins) { this.zone4Mins = zone4Mins; }

    public BigDecimal getZone5Mins() { return zone5Mins; }
    public void setZone5Mins(BigDecimal zone5Mins) { this.zone5Mins = zone5Mins; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
