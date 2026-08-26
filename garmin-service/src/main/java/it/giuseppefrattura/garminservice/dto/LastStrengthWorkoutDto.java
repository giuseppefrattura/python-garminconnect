package it.giuseppefrattura.garminservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Latest strength training session with its set breakdown and volume analytics.
 */
public record LastStrengthWorkoutDto(
        Long activityId,
        String activityName,
        String startTimeLocal,
        String duration,
        Integer durationSeconds,
        Integer calories,
        Integer averageHR,
        Integer maxHR,
        BigDecimal aerobicTrainingEffect,
        BigDecimal anaerobicTrainingEffect,
        List<WorkoutSetDto> sets,
        Map<String, Double> volumeByMuscleGroup
) {

    /**
     * A single exercise set of the latest session, including PR flags.
     */
    public record WorkoutSetDto(
            Long setId,
            Integer setNumber,
            String exercise,
            String originalExercise,
            String muscleGroup,
            Integer reps,
            double weightKg,
            @JsonProperty("isPr") boolean isPr,
            List<String> prTypes
    ) {}
}
