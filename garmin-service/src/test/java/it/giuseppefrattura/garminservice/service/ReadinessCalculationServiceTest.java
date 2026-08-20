package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessCalculationServiceTest {

    private ReadinessCalculationService readinessService;

    @BeforeEach
    void setUp() {
        readinessService = new ReadinessCalculationService();
    }

    @Test
    void testCalculateReadiness_OptimalRecovery() {
        DailyHealthMetric metric = new DailyHealthMetric(LocalDate.now());
        metric.setSleepScore(90);
        metric.setBodyBatteryWake(95);
        metric.setHrvStatus("BALANCED");
        metric.setAvgStressLevel(20); // Inverted = 80

        ReadinessCalculationService.ReadinessResult result = readinessService.calculateReadiness(metric);

        assertNotNull(result);
        assertTrue(result.getScore() >= 80, "Expected score >= 80 for optimal metrics");
        assertEquals("OPTIMAL", result.getLevel());
        assertTrue(result.getAdvice().contains("Recupero eccellente"));
    }

    @Test
    void testCalculateReadiness_FatiguedRecovery() {
        DailyHealthMetric metric = new DailyHealthMetric(LocalDate.now());
        metric.setSleepScore(45);
        metric.setBodyBatteryWake(35);
        metric.setHrvStatus("LOW");
        metric.setAvgStressLevel(65); // Inverted = 35

        ReadinessCalculationService.ReadinessResult result = readinessService.calculateReadiness(metric);

        assertNotNull(result);
        assertTrue(result.getScore() < 55, "Expected score < 55 for fatigued metrics");
        assertEquals("FATIGUED", result.getLevel());
        assertTrue(result.getAdvice().contains("affaticamento"));
    }

    @Test
    void testCalculateReadiness_ModerateRecovery() {
        DailyHealthMetric metric = new DailyHealthMetric(LocalDate.now());
        metric.setSleepScore(70);
        metric.setBodyBatteryWake(65);
        metric.setHrvStatus("BALANCED");
        metric.setAvgStressLevel(35);

        ReadinessCalculationService.ReadinessResult result = readinessService.calculateReadiness(metric);

        assertNotNull(result);
        assertTrue(result.getScore() >= 55 && result.getScore() < 80);
        assertEquals("MODERATE", result.getLevel());
    }

    @Test
    void testCalculateReadiness_NullMetricFallback() {
        ReadinessCalculationService.ReadinessResult result = readinessService.calculateReadiness(null);

        assertNotNull(result);
        assertEquals(70, result.getScore());
        assertEquals("MODERATE", result.getLevel());
    }
}
