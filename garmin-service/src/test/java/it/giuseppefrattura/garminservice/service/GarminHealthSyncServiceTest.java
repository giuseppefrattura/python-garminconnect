package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import it.giuseppefrattura.garminservice.repository.DailyHealthMetricRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class GarminHealthSyncServiceTest {

    @Mock
    private GarminProxyClient proxyClient;

    @Mock
    private DailyHealthMetricRepository healthRepository;

    @Mock
    private ReadinessCalculationService readinessService;

    private GarminHealthSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new GarminHealthSyncService(proxyClient, healthRepository, readinessService);
    }

    @Test
    void testSyncHealthMetricForDate_ParsesCorrectly() {
        LocalDate date = LocalDate.of(2026, 8, 20);

        Map<String, Object> sleepMap = new HashMap<>();
        Map<String, Object> dailySleepDTO = new HashMap<>();
        dailySleepDTO.put("sleepTimeSeconds", 28800);
        dailySleepDTO.put("deepSleepSeconds", 7200);
        dailySleepDTO.put("lightSleepSeconds", 14400);
        dailySleepDTO.put("remSleepSeconds", 5400);
        dailySleepDTO.put("awakeSleepSeconds", 1800);
        dailySleepDTO.put("sleepScores", Map.of("overall", Map.of("value", 85, "qualifierKey", "EXCELLENT")));
        sleepMap.put("dailySleepDTO", dailySleepDTO);
        sleepMap.put("restingHeartRate", 52);

        Map<String, Object> hrvMap = new HashMap<>();
        hrvMap.put("hrvSummary", Map.of("lastNightAvg", 65.0, "weeklyAvg", 63.0, "status", "BALANCED"));

        Map<String, Object> stressMap = new HashMap<>();
        stressMap.put("avgStressLevel", 25);
        stressMap.put("maxStressLevel", 70);

        Map<String, Object> summary = new HashMap<>();
        summary.put("sleep", sleepMap);
        summary.put("body_battery", List.of(Map.of("charged", 45, "drained", 60, "bodyBatteryValuesArray", List.of(List.of(1000, 80), List.of(2000, 40)))));
        summary.put("hrv", hrvMap);
        summary.put("stress", stressMap);

        when(proxyClient.getDailyHealthSummary("2026-08-20")).thenReturn(summary);
        when(healthRepository.findByMetricDate(date)).thenReturn(Optional.empty());
        when(readinessService.calculateReadiness(any())).thenReturn(
                new ReadinessCalculationService.ReadinessResult(88, "OPTIMAL", "Recupero eccellente!")
        );
        when(healthRepository.save(any(DailyHealthMetric.class))).thenAnswer(inv -> inv.getArgument(0));

        DailyHealthMetric saved = syncService.syncHealthMetricForDate(date);

        assertNotNull(saved);
        assertEquals(85, saved.getSleepScore());
        assertEquals("EXCELLENT", saved.getSleepQualifier());
        assertEquals(28800, saved.getSleepDurationSeconds());
        assertEquals(52, saved.getRestingHeartRate());
        assertEquals(65.0, saved.getHrvNightlyAvg());
        assertEquals("BALANCED", saved.getHrvStatus());
        assertEquals(25, saved.getAvgStressLevel());
        assertEquals(88, saved.getReadinessScore());
        assertEquals("OPTIMAL", saved.getReadinessLevel());

        verify(healthRepository).save(any(DailyHealthMetric.class));
    }
}
