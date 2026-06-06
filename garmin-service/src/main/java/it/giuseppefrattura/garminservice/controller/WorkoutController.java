package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.service.StrengthWorkoutService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
     * set breakdown and volume by muscle group.
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
}
