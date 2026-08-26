package it.giuseppefrattura.garminservice.dto;

/**
 * Single point of the historical progression curve for an exercise.
 */
public record ProgressionPointDto(
        String date,
        double maxWeightKg,
        double estimated1RM
) {}
