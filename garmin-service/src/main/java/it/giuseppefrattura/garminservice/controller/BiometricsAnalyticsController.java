package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.service.BiometricsAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/biometrics")
public class BiometricsAnalyticsController {

    private final BiometricsAnalyticsService biometricsService;

    public BiometricsAnalyticsController(BiometricsAnalyticsService biometricsService) {
        this.biometricsService = biometricsService;
    }

    /**
     * Get Relative Strength (1RM / Body Weight and 1RM / FFM) for key exercises.
     */
    @GetMapping("/relative-strength")
    public ResponseEntity<Map<String, Object>> getRelativeStrength() {
        Map<String, Object> data = biometricsService.getRelativeStrengthOverview();
        return ResponseEntity.ok(data);
    }

    /**
     * Get body recomposition trend: Weight & Body Fat % vs Workout Tonnage.
     */
    @GetMapping("/recomposition-trend")
    public ResponseEntity<Map<String, Object>> getRecompositionTrend() {
        Map<String, Object> data = biometricsService.getBodyRecompositionTrend();
        return ResponseEntity.ok(data);
    }
}
