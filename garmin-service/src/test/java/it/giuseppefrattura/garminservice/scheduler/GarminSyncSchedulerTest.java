package it.giuseppefrattura.garminservice.scheduler;

import it.giuseppefrattura.garminservice.model.SyncAuditLog;
import it.giuseppefrattura.garminservice.repository.SyncAuditLogRepository;
import it.giuseppefrattura.garminservice.service.GarminHealthSyncService;
import it.giuseppefrattura.garminservice.service.RunHrZoneService;
import it.giuseppefrattura.garminservice.service.StrengthWorkoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "null"})
class GarminSyncSchedulerTest {

    @Mock
    private RunHrZoneService hrZoneService;

    @Mock
    private StrengthWorkoutService strengthWorkoutService;

    @Mock
    private GarminHealthSyncService healthSyncService;

    @Mock
    private SyncAuditLogRepository auditLogRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private it.giuseppefrattura.garminservice.service.CustomMetricsService customMetricsService;

    private GarminSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GarminSyncScheduler(
                hrZoneService,
                strengthWorkoutService,
                healthSyncService,
                auditLogRepository,
                restTemplate,
                customMetricsService
        );
        when(auditLogRepository.save(any(SyncAuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void testPerformMidnightSync_Success() {
        when(strengthWorkoutService.syncStrengthWorkouts(anyInt())).thenReturn(3);
        when(hrZoneService.persistRunHrZones(anyInt())).thenReturn(Map.of("status", "success"));
        when(healthSyncService.syncRecentHealthMetrics(anyInt())).thenReturn(Map.of("syncedDays", 7));

        Map<String, Object> renphoBody = Map.of("syncedCount", 2);
        ResponseEntity<Map<String, Object>> renphoResponse = new ResponseEntity<>(renphoBody, HttpStatus.OK);
        when(restTemplate.exchange(
                contains("/api/renpho/sync"),
                eq(HttpMethod.POST),
                any(),
                any(ParameterizedTypeReference.class)
        )).thenReturn(renphoResponse);

        SyncAuditLog log = scheduler.performMidnightSync("TEST", "MIDNIGHT_TEST");

        assertNotNull(log);
        assertEquals("SUCCESS", log.getStatus());
        assertEquals(3, log.getGarminWorkoutsCount());
        assertEquals(7, log.getGarminHealthDays());
        assertEquals(2, log.getRenphoMeasurementsCount());
        assertTrue(log.getDetails().contains("Renpho: 2 weigh-ins"));
        verify(auditLogRepository, atLeastOnce()).save(any(SyncAuditLog.class));
        verify(customMetricsService).recordSyncResult(eq("SUCCESS"), anyDouble(), eq(3), eq(7), eq(2));
    }

    @Test
    void testPerformMidnightSync_PartialFailureWhenWorkoutThrows() {
        doThrow(new RuntimeException("Garmin API down")).when(strengthWorkoutService).syncStrengthWorkouts(anyInt());
        when(hrZoneService.persistRunHrZones(anyInt())).thenReturn(Map.of("status", "success"));
        when(healthSyncService.syncRecentHealthMetrics(anyInt())).thenReturn(Map.of("syncedDays", 5));

        Map<String, Object> renphoBody = Map.of("syncedCount", 1);
        ResponseEntity<Map<String, Object>> renphoResponse = new ResponseEntity<>(renphoBody, HttpStatus.OK);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(),
                any(ParameterizedTypeReference.class)
        )).thenReturn(renphoResponse);

        SyncAuditLog log = scheduler.performMidnightSync("TEST", "MIDNIGHT_TEST");

        assertNotNull(log);
        assertEquals("PARTIAL", log.getStatus());
        assertTrue(log.getDetails().contains("Garmin API down"));
    }
}
