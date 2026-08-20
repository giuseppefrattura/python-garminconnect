package it.giuseppefrattura.garminservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * JPA entity mapping the {@code strength_workout_set} PostgreSQL table.
 */
@Entity
@Table(name = "strength_workout_set")
public class StrengthWorkoutSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workout_id", nullable = false)
    @JsonIgnore
    private StrengthWorkout workout;

    @Column(name = "set_number", nullable = false)
    private Integer setNumber;

    @Column(name = "original_exercise_name", length = 255)
    private String originalExerciseName;

    @Column(name = "exercise_name", length = 255)
    private String exerciseName;

    @Column(name = "reps")
    private Integer reps;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    public StrengthWorkoutSet() {}

    public StrengthWorkoutSet(StrengthWorkout workout, Integer setNumber, String originalExerciseName, String exerciseName, Integer reps, BigDecimal weightKg) {
        this.workout = workout;
        this.setNumber = setNumber;
        this.originalExerciseName = originalExerciseName;
        this.exerciseName = exerciseName;
        this.reps = reps;
        this.weightKg = weightKg;
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public StrengthWorkout getWorkout() { return workout; }
    public void setWorkout(StrengthWorkout workout) { this.workout = workout; }

    public Integer getSetNumber() { return setNumber; }
    public void setSetNumber(Integer setNumber) { this.setNumber = setNumber; }

    public String getOriginalExerciseName() { return originalExerciseName; }
    public void setOriginalExerciseName(String originalExerciseName) { this.originalExerciseName = originalExerciseName; }

    public String getExerciseName() { return exerciseName; }
    public void setExerciseName(String exerciseName) { this.exerciseName = exerciseName; }

    public Integer getReps() { return reps; }
    public void setReps(Integer reps) { this.reps = reps; }

    public BigDecimal getWeightKg() { return weightKg; }
    public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
}
