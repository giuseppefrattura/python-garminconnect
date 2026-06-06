package it.giuseppefrattura.garminservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity mapping an original Garmin exercise name to a custom user-defined name.
 */
@Entity
@Table(name = "exercise_name_mapping")
public class ExerciseNameMapping {

    @Id
    @Column(name = "original_name")
    private String originalName;

    @Column(name = "custom_name", nullable = false)
    private String customName;

    public ExerciseNameMapping() {}

    public ExerciseNameMapping(String originalName, String customName) {
        this.originalName = originalName;
        this.customName = customName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getCustomName() {
        return customName;
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }
}
