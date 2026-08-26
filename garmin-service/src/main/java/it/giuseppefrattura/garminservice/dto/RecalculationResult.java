package it.giuseppefrattura.garminservice.dto;

/**
 * Outcome of a full personal-record recalculation.
 */
public record RecalculationResult(
        int totalPrsSaved,
        int distinctExercises
) {}
