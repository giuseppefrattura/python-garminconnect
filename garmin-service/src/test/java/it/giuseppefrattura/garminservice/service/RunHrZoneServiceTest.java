package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ActivityDto.ActivityTypeDto;
import it.giuseppefrattura.garminservice.dto.HrZoneDto;
import it.giuseppefrattura.garminservice.model.RunningHrZone;
import it.giuseppefrattura.garminservice.repository.RunningHrZoneRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class RunHrZoneServiceTest {

    @Mock
    private GarminProxyClient proxy;

    @Mock
    private RunningHrZoneRepository repository;

    @Mock
    private StrengthWorkoutService strengthWorkoutService;

    @InjectMocks
    private RunHrZoneService service;

    private ActivityDto runActivity(long id, String name, String startTime) {
        return new ActivityDto(id, name,
                new ActivityTypeDto("running"),
                startTime, 3600.0, 400, 145, 175, 3.0, 1.0);
    }

    private List<HrZoneDto> zones(double z1Secs, double z2Secs, double z3Secs,
                                   double z4Secs, double z5Secs) {
        return List.of(
                new HrZoneDto(1, z1Secs),
                new HrZoneDto(2, z2Secs),
                new HrZoneDto(3, z3Secs),
                new HrZoneDto(4, z4Secs),
                new HrZoneDto(5, z5Secs)
        );
    }

    // -----------------------------------------------------------------------
    // getRunHrZones (read-only aggregation)
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("getRunHrZones — read-only aggregation")
    class GetRunHrZones {

        @Test
        @DisplayName("Returns empty result when no running activities found")
        void emptyWhenNoActivities() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of());

            Map<String, Object> result = service.getRunHrZones(10);

            assertEquals("success", result.get("status"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(0, data.get("activitiesCount"));
        }

        @Test
        @DisplayName("Converts seconds to minutes and aggregates across activities")
        void convertSecsToMinsAndAggregate() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Morning Run", "2024-01-10 07:00:00"),
                            runActivity(222, "Evening Run", "2024-01-11 18:00:00")
                    ));
            // Activity 111: 10min Z1, 20min Z2
            when(proxy.getHrZones(111)).thenReturn(zones(600, 1200, 0, 0, 0));
            // Activity 222: 5min Z2, 30min Z3
            when(proxy.getHrZones(222)).thenReturn(zones(0, 300, 1800, 0, 0));

            Map<String, Object> result = service.getRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(2, data.get("activitiesCount"));

            @SuppressWarnings("unchecked")
            Map<String, Double> zones = (Map<String, Double>) data.get("zones");
            assertEquals(10.0, zones.get("zone_1"));   // 600s / 60
            assertEquals(25.0, zones.get("zone_2"));   // (1200+300) / 60
            assertEquals(30.0, zones.get("zone_3"));   // 1800s / 60
        }

        @Test
        @DisplayName("Per-activity breakdown shows only non-zero zones")
        void perActivityBreakdown() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Run", "2024-01-10 07:00:00")
                    ));
            when(proxy.getHrZones(111)).thenReturn(zones(0, 1200, 0, 0, 0));

            Map<String, Object> result = service.getRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> activities = (List<Map<String, Object>>) data.get("activities");

            assertEquals(1, activities.size());

            @SuppressWarnings("unchecked")
            Map<String, Double> actZones = (Map<String, Double>) activities.get(0).get("zones");
            // Only zone_2 should be present (non-zero)
            assertTrue(actZones.containsKey("zone_2"));
            assertFalse(actZones.containsKey("zone_1"), "Zero zones should be omitted");
            assertFalse(actZones.containsKey("zone_3"), "Zero zones should be omitted");
        }

        @Test
        @DisplayName("Handles HR zone fetch error for one activity gracefully")
        void handlesPartialError() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Good Run", "2024-01-10 07:00:00"),
                            runActivity(222, "Bad Run", "2024-01-11 07:00:00")
                    ));
            when(proxy.getHrZones(111)).thenReturn(zones(600, 0, 0, 0, 0));
            when(proxy.getHrZones(222)).thenThrow(new RuntimeException("Connection timeout"));

            Map<String, Object> result = service.getRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(2, data.get("activitiesCount"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> activities = (List<Map<String, Object>>) data.get("activities");
            // First activity should have zones
            assertTrue(activities.get(0).containsKey("zones"));
            // Second activity should have error
            assertTrue(activities.get(1).containsKey("error"));
        }

        @Test
        @DisplayName("Ignores HR zone numbers outside 1-5 range")
        void ignoresInvalidZoneNumbers() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Run", "2024-01-10 07:00:00")
                    ));
            when(proxy.getHrZones(111)).thenReturn(List.of(
                    new HrZoneDto(1, 600.0),
                    new HrZoneDto(6, 999.0),   // invalid zone
                    new HrZoneDto(0, 999.0)    // invalid zone
            ));

            Map<String, Object> result = service.getRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Double> zones = (Map<String, Double>) data.get("zones");

            assertEquals(10.0, zones.get("zone_1"));
            assertFalse(zones.containsKey("zone_6"));
            assertFalse(zones.containsKey("zone_0"));
        }
    }

    // -----------------------------------------------------------------------
    // persistRunHrZones (with DB upsert)
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("persistRunHrZones — DB upsert")
    class PersistRunHrZones {

        @Test
        @DisplayName("Returns empty result when no activities found")
        void emptyWhenNoActivities() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of());

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(0, data.get("activitiesCount"));
            assertEquals(0, data.get("savedCount"));
        }

        @Test
        @DisplayName("Creates new entity when activity not in DB")
        void createsNewEntity() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(333, "New Run", "2024-01-10 08:30:00")
                    ));
            when(proxy.getHrZones(333)).thenReturn(zones(600, 1200, 1800, 0, 0));
            when(repository.findByActivityId(333L)).thenReturn(Optional.empty());
            when(repository.save(any(RunningHrZone.class))).thenAnswer(i -> i.getArgument(0));

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(1, data.get("savedCount"));

            // Verify entity was saved with correct values
            ArgumentCaptor<RunningHrZone> captor = ArgumentCaptor.forClass(RunningHrZone.class);
            verify(repository).save(captor.capture());

            RunningHrZone saved = captor.getValue();
            assertEquals(333L, saved.getActivityId());
            assertEquals("New Run", saved.getActivityName());
            assertEquals(new BigDecimal("10.00"), saved.getZone1Mins());
            assertEquals(new BigDecimal("20.00"), saved.getZone2Mins());
            assertEquals(new BigDecimal("30.00"), saved.getZone3Mins());
            assertEquals(new BigDecimal("0.00"), saved.getZone4Mins());
            assertEquals(new BigDecimal("0.00"), saved.getZone5Mins());
        }

        @Test
        @DisplayName("Updates existing entity on upsert (same activityId)")
        void updatesExistingEntity() {
            RunningHrZone existing = new RunningHrZone();
            existing.setActivityId(333L);
            existing.setActivityName("Old Name");

            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(333, "Updated Run", "2024-01-10 08:30:00")
                    ));
            when(proxy.getHrZones(333)).thenReturn(zones(300, 600, 0, 0, 0));
            when(repository.findByActivityId(333L)).thenReturn(Optional.of(existing));
            when(repository.save(any(RunningHrZone.class))).thenAnswer(i -> i.getArgument(0));

            service.persistRunHrZones(10);

            ArgumentCaptor<RunningHrZone> captor = ArgumentCaptor.forClass(RunningHrZone.class);
            verify(repository).save(captor.capture());

            RunningHrZone updated = captor.getValue();
            assertSame(existing, updated, "Should reuse the existing entity (upsert)");
            assertEquals("Updated Run", updated.getActivityName());
        }

        @Test
        @DisplayName("Saves multiple activities in one call")
        void savesMultipleActivities() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Run A", "2024-01-10 07:00:00"),
                            runActivity(222, "Run B", "2024-01-11 07:00:00")
                    ));
            when(proxy.getHrZones(111)).thenReturn(zones(600, 0, 0, 0, 0));
            when(proxy.getHrZones(222)).thenReturn(zones(0, 1200, 0, 0, 0));
            when(repository.findByActivityId(anyLong())).thenReturn(Optional.empty());
            when(repository.save(any(RunningHrZone.class))).thenAnswer(i -> i.getArgument(0));

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(2, data.get("activitiesCount"));
            assertEquals(2, data.get("savedCount"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> saved = (List<Map<String, Object>>) data.get("saved");
            assertEquals(111L, saved.get(0).get("activityId"));
            assertEquals(10.0, saved.get(0).get("zone1Mins"));
            assertEquals(222L, saved.get(1).get("activityId"));
            assertEquals(20.0, saved.get(1).get("zone2Mins"));
        }

        @Test
        @DisplayName("Skips activities with unparseable startTimeLocal (no date/time)")
        void skipsUnparseableDate() {
            ActivityDto badDateActivity = new ActivityDto(444L, "Bad Date Run",
                    new ActivityTypeDto("running"),
                    "invalid-date", 3600.0, 400, 145, 175, 3.0, 1.0);

            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(badDateActivity));
            when(proxy.getHrZones(444)).thenReturn(zones(600, 0, 0, 0, 0));

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(1, data.get("activitiesCount"));
            assertEquals(0, data.get("savedCount"), "Activity with invalid date should be skipped");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Handles DB save error gracefully without failing the whole batch")
        void handlesDbErrorGracefully() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(111, "Run A", "2024-01-10 07:00:00"),
                            runActivity(222, "Run B", "2024-01-11 07:00:00")
                    ));
            when(proxy.getHrZones(111)).thenReturn(zones(600, 0, 0, 0, 0));
            when(proxy.getHrZones(222)).thenReturn(zones(0, 1200, 0, 0, 0));
            when(repository.findByActivityId(anyLong())).thenReturn(Optional.empty());

            // First save throws, second succeeds
            when(repository.save(any(RunningHrZone.class)))
                    .thenThrow(new RuntimeException("DB constraint violation"))
                    .thenAnswer(i -> i.getArgument(0));

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(2, data.get("activitiesCount"));
            assertEquals(1, data.get("savedCount"), "Only the successful save should be counted");
        }

        @Test
        @DisplayName("Returned saved records contain correct zone minutes rounded to 2 decimals")
        void savedRecordsHaveRoundedValues() {
            when(proxy.getActivitiesByDate(anyString(), anyString(), eq("running")))
                    .thenReturn(List.of(
                            runActivity(555, "Tempo Run", "2024-02-15 06:45:00")
                    ));
            // 7 min 30 sec = 450 secs → 7.5 min
            when(proxy.getHrZones(555)).thenReturn(zones(450, 0, 0, 0, 0));
            when(repository.findByActivityId(555L)).thenReturn(Optional.empty());
            when(repository.save(any(RunningHrZone.class))).thenAnswer(i -> i.getArgument(0));

            Map<String, Object> result = service.persistRunHrZones(10);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> saved = (List<Map<String, Object>>) data.get("saved");

            assertEquals(7.5, saved.get(0).get("zone1Mins"));
        }
    }
}
