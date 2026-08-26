package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.dto.ApiResponse;
import it.giuseppefrattura.garminservice.dto.LastStrengthWorkoutDto;
import it.giuseppefrattura.garminservice.dto.ProgressionPointDto;
import it.giuseppefrattura.garminservice.service.StrengthWorkoutService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

/**
 * REST controller for strength/weight training endpoints.
 */
@RestController
@RequestMapping("/api")
public class WorkoutController {

    private final StrengthWorkoutService service;
    private final int defaultLimit;

    public WorkoutController(
            StrengthWorkoutService service,
            @Value("${garmin.workouts.search-limit:30}") int defaultLimit) {
        this.service = service;
        this.defaultLimit = defaultLimit;
    }

    /**
     * Returns the most recent strength training activity with detailed
     * set breakdown and volume by muscle group from database.
     */
    @GetMapping("/last-strength-workout")
    public ResponseEntity<ApiResponse<LastStrengthWorkoutDto>> lastStrengthWorkout() {
        ApiResponse<LastStrengthWorkoutDto> result = service.getLastStrengthWorkout();
        if (result.isError()) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the full history of strength workouts (weekly volume per muscle group).
     */
    @GetMapping("/strength-workouts-history")
    public ResponseEntity<ApiResponse<Map<String, Map<String, Double>>>> strengthWorkoutsHistory() {
        return ResponseEntity.ok(service.getWorkoutHistory());
    }

    /**
     * Returns performance progression metrics for a specific exercise over time.
     */
    @GetMapping("/exercise-progression")
    public ResponseEntity<ApiResponse<List<ProgressionPointDto>>> exerciseProgression(
            @RequestParam("exercise") String exercise) {
        ApiResponse<List<ProgressionPointDto>> result = service.getExerciseProgression(exercise);
        if (result.isError()) {
            return ResponseEntity.status(400).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns a list of all unique exercise names performed.
     */
    @GetMapping("/strength-exercises")
    public ResponseEntity<List<String>> strengthExercises() {
        return ResponseEntity.ok(service.getUniqueExercises());
    }

    /**
     * Update a specific set's name, muscle group, weight (kg), and/or reps by database ID.
     */
    @PostMapping({"/exercise-set/name", "/exercise-set"})
    public ResponseEntity<Map<String, Object>> saveSetDetails(
            @RequestParam("setId") Long setId,
            @RequestParam(value = "customName", required = false) String customName,
            @RequestParam(value = "muscleGroup", required = false) String muscleGroup,
            @RequestParam(value = "weightKg", required = false) Double weightKg,
            @RequestParam(value = "reps", required = false) Integer reps,
            @RequestParam(value = "applyToAllSimilar", required = false, defaultValue = "false") Boolean applyToAllSimilar) {
        try {
            service.updateSetDetails(setId, customName, muscleGroup, weightKg, reps, applyToAllSimilar);
            return ResponseEntity.ok(Map.of("status", "success", "detail", "Set details updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("status", "error", "detail", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "error", "detail", "Failed to update set details: " + e.getMessage()));
        }
    }

    /**
     * Synchronize strength workouts from Garmin Connect into PostgreSQL.
     */
    @PostMapping("/sync/strength-workouts")
    public ResponseEntity<Map<String, Object>> syncStrengthWorkouts(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int count = service.syncStrengthWorkouts(limit != null ? limit : defaultLimit);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Strength workouts synchronization completed",
                "syncedCount", count
        ));
    }
}
