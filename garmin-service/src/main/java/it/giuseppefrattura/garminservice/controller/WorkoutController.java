package it.giuseppefrattura.garminservice.controller;

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
    public ResponseEntity<Map<String, Object>> lastStrengthWorkout(
            @RequestParam(value = "limit", required = false) Integer limit) {
        Map<String, Object> result = service.getLastStrengthWorkout(
                limit != null ? limit : defaultLimit);
        if ("error".equals(result.get("status"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the full history of strength workouts (weekly volume per muscle group).
     */
    @GetMapping("/strength-workouts-history")
    public ResponseEntity<Map<String, Object>> strengthWorkoutsHistory() {
        return ResponseEntity.ok(service.getWorkoutHistory());
    }

    /**
     * Returns performance progression metrics for a specific exercise over time.
     */
    @GetMapping("/exercise-progression")
    public ResponseEntity<Map<String, Object>> exerciseProgression(
            @RequestParam("exercise") String exercise) {
        Map<String, Object> result = service.getExerciseProgression(exercise);
        if ("error".equals(result.get("status"))) {
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
     * Rename a specific set by database ID.
     */
    @PostMapping("/exercise-set/name")
    public ResponseEntity<Map<String, Object>> saveSetExerciseName(
            @RequestParam("setId") Long setId,
            @RequestParam(value = "customName", required = false) String customName) {
        try {
            service.updateSetExerciseName(setId, customName);
            return ResponseEntity.ok(Map.of("status", "success", "detail", "Set exercise name updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("status", "error", "detail", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "error", "detail", "Failed to update set exercise name: " + e.getMessage()));
        }
    }
}
