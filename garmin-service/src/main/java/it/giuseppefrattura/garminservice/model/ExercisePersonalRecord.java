package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "exercise_personal_record", indexes = {
    @Index(name = "idx_pr_exercise_type", columnList = "exercise_name, record_type"),
    @Index(name = "idx_pr_activity_id", columnList = "activity_id"),
    @Index(name = "idx_pr_set_id", columnList = "set_id")
})
public class ExercisePersonalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exercise_name", nullable = false, length = 100)
    private String exerciseName;

    @Column(name = "muscle_group", length = 50)
    private String muscleGroup;

    @Column(name = "record_type", nullable = false, length = 30)
    private String recordType; // MAX_WEIGHT, MAX_1RM, MAX_VOLUME_SET, MAX_REPS

    @Column(name = "record_value", nullable = false)
    private Double recordValue;

    @Column(name = "weight_kg", nullable = false)
    private Double weightKg;

    @Column(name = "reps", nullable = false)
    private Integer reps;

    @Column(name = "activity_id")
    private Long activityId;

    @Column(name = "set_id")
    private Long setId;

    @Column(name = "achieved_at", nullable = false)
    private LocalDateTime achievedAt;

    public ExercisePersonalRecord() {
    }

    public ExercisePersonalRecord(String exerciseName, String muscleGroup, String recordType, Double recordValue,
                                  Double weightKg, Integer reps, Long activityId, Long setId, LocalDateTime achievedAt) {
        this.exerciseName = exerciseName;
        this.muscleGroup = muscleGroup;
        this.recordType = recordType;
        this.recordValue = recordValue;
        this.weightKg = weightKg;
        this.reps = reps;
        this.activityId = activityId;
        this.setId = setId;
        this.achievedAt = achievedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExerciseName() {
        return exerciseName;
    }

    public void setExerciseName(String exerciseName) {
        this.exerciseName = exerciseName;
    }

    public String getMuscleGroup() {
        return muscleGroup;
    }

    public void setMuscleGroup(String muscleGroup) {
        this.muscleGroup = muscleGroup;
    }

    public String getRecordType() {
        return recordType;
    }

    public void setRecordType(String recordType) {
        this.recordType = recordType;
    }

    public Double getRecordValue() {
        return recordValue;
    }

    public void setRecordValue(Double recordValue) {
        this.recordValue = recordValue;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(Double weightKg) {
        this.weightKg = weightKg;
    }

    public Integer getReps() {
        return reps;
    }

    public void setReps(Integer reps) {
        this.reps = reps;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getSetId() {
        return setId;
    }

    public void setSetId(Long setId) {
        this.setId = setId;
    }

    public LocalDateTime getAchievedAt() {
        return achievedAt;
    }

    public void setAchievedAt(LocalDateTime achievedAt) {
        this.achievedAt = achievedAt;
    }
}
