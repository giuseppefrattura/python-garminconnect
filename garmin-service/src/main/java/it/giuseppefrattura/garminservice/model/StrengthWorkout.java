package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity mapping the {@code strength_workout} PostgreSQL table.
 */
@Entity
@Table(name = "strength_workout")
public class StrengthWorkout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", nullable = false, unique = true)
    private Long activityId;

    @Column(name = "activity_name", length = 255)
    private String activityName;

    @Column(name = "workout_date", nullable = false)
    private LocalDate workoutDate;

    @Column(name = "workout_time", nullable = false)
    private LocalTime workoutTime;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "calories")
    private Integer calories;

    @Column(name = "average_hr")
    private Integer averageHr;

    @Column(name = "max_hr")
    private Integer maxHr;

    @Column(name = "aerobic_te", precision = 3, scale = 1)
    private BigDecimal aerobicTe;

    @Column(name = "anaerobic_te", precision = 3, scale = 1)
    private BigDecimal anaerobicTe;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "workout", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("setNumber ASC")
    private List<StrengthWorkoutSet> sets = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }

    public LocalDate getWorkoutDate() { return workoutDate; }
    public void setWorkoutDate(LocalDate workoutDate) { this.workoutDate = workoutDate; }

    public LocalTime getWorkoutTime() { return workoutTime; }
    public void setWorkoutTime(LocalTime workoutTime) { this.workoutTime = workoutTime; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Integer getCalories() { return calories; }
    public void setCalories(Integer calories) { this.calories = calories; }

    public Integer getAverageHr() { return averageHr; }
    public void setAverageHr(Integer averageHr) { this.averageHr = averageHr; }

    public Integer getMaxHr() { return maxHr; }
    public void setMaxHr(Integer maxHr) { this.maxHr = maxHr; }

    public BigDecimal getAerobicTe() { return aerobicTe; }
    public void setAerobicTe(BigDecimal aerobicTe) { this.aerobicTe = aerobicTe; }

    public BigDecimal getAnaerobicTe() { return anaerobicTe; }
    public void setAnaerobicTe(BigDecimal anaerobicTe) { this.anaerobicTe = anaerobicTe; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<StrengthWorkoutSet> getSets() { return sets; }
    public void setSets(List<StrengthWorkoutSet> sets) { this.sets = sets; }

    public void addSet(StrengthWorkoutSet set) {
        sets.add(set);
        set.setWorkout(this);
    }
}
