package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ActivityDto.ActivityTypeDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseSetDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrengthWorkoutServiceTest {

    @Mock
    private GarminProxyClient proxy;

    @InjectMocks
    private StrengthWorkoutService service;

    private ActivityDto strengthActivity(long id, String name) {
        return new ActivityDto(id, name,
                new ActivityTypeDto("strength_training"),
                "2024-01-15 09:00:00", 1800.0, 250, 120, 155, 2.1, 3.5);
    }

    private ActivityDto runningActivity(long id, String name) {
        return new ActivityDto(id, name,
                new ActivityTypeDto("running"),
                "2024-01-15 08:00:00", 3600.0, 500, 145, 175, 3.0, 1.0);
    }

    private ExerciseSetDto activeSet(String category, String name, int reps, double weightGrams) {
        return new ExerciseSetDto("ACTIVE", reps, weightGrams,
                List.of(new ExerciseDto(category, name)));
    }

    private ExerciseSetDto restSet() {
        return new ExerciseSetDto("REST", null, null, null);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("Returns latest strength workout with sets and volume")
        void returnsStrengthWorkoutWithSetsAndVolume() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(
                    runningActivity(1, "Morning Run"),
                    strengthActivity(2, "Chest Day")
            ));
            when(proxy.getExerciseSets(2)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("BENCH_PRESS", "bench_press", 10, 80000.0),  // 80 kg
                    restSet(),
                    activeSet("BENCH_PRESS", "bench_press", 8, 80000.0)   // 80 kg
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            assertEquals("success", result.get("status"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(2L, data.get("activityId"));
            assertEquals("Chest Day", data.get("activityName"));
            assertEquals("00:30:00", data.get("duration"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sets = (List<Map<String, Object>>) data.get("sets");
            assertEquals(2, sets.size(), "REST sets should be filtered out");

            // Set numbering should be sequential (no gaps from REST filtering)
            assertEquals(1, sets.get(0).get("setNumber"));
            assertEquals(2, sets.get(1).get("setNumber"));

            // Weight conversion: 80000 grams / 1000 = 80.0 kg
            assertEquals(80.0, sets.get(0).get("weightKg"));

            @SuppressWarnings("unchecked")
            Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");
            // 10*80 + 8*80 = 1440 kg total volume
            assertEquals(1440.0, volume.get("Chest"));
        }

        @Test
        @DisplayName("Finds strength activity even when typeKey contains 'training'")
        void findsActivityByTrainingKeyword() {
            ActivityDto trainingActivity = new ActivityDto(5L, "Gym Session",
                    new ActivityTypeDto("other_training"),
                    "2024-01-15 09:00:00", 1800.0, 200, 110, 140, 1.5, 2.0);

            when(proxy.getActivities(0, 30)).thenReturn(List.of(trainingActivity));
            when(proxy.getExerciseSets(5)).thenReturn(new ExerciseSetsResponse(List.of()));

            Map<String, Object> result = service.getLastStrengthWorkout(30);
            assertEquals("success", result.get("status"));
        }
    }

    @Nested
    @DisplayName("No strength activity found")
    class NoStrengthActivity {

        @Test
        @DisplayName("Returns error when no strength activity in last 30")
        void returnsErrorWhenNoStrength() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(
                    runningActivity(1, "Run 1"),
                    runningActivity(2, "Run 2")
            ));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            assertEquals("error", result.get("status"));
            assertTrue(result.get("detail").toString().contains("No strength training"));
        }

        @Test
        @DisplayName("Returns error when activity list is empty")
        void returnsErrorWhenEmpty() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of());

            Map<String, Object> result = service.getLastStrengthWorkout(30);
            assertEquals("error", result.get("status"));
        }
    }

    @Nested
    @DisplayName("Weight conversion")
    class WeightConversion {

        @Test
        @DisplayName("Converts weight from milligrams to kilograms")
        void convertsMilligramsToKg() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("SQUAT", "squat", 5, 120000.0)  // 120 kg
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sets = (List<Map<String, Object>>) data.get("sets");

            assertEquals(120.0, sets.get(0).get("weightKg"));
        }

        @Test
        @DisplayName("Handles zero weight gracefully")
        void handlesZeroWeight() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("PLANK", "plank", 1, 0.0)
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sets = (List<Map<String, Object>>) data.get("sets");

            assertEquals(0.0, sets.get(0).get("weightKg"));

            // Zero weight → no volume contribution
            @SuppressWarnings("unchecked")
            Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");
            assertFalse(volume.containsKey("Core"), "Zero-weight exercise should not contribute volume");
        }
    }

    @Nested
    @DisplayName("Muscle group mapping")
    class MuscleGroupMapping {

        @Test
        @DisplayName("Maps known Garmin categories to human-readable groups")
        void mapsKnownCategories() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Full Body")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("BENCH_PRESS", "bench_press", 10, 50000.0),
                    activeSet("SQUAT", "squat", 10, 100000.0),
                    activeSet("BICEP_CURL", "bicep_curl", 12, 15000.0),
                    activeSet("DEADLIFT", "deadlift", 5, 140000.0)
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");

            assertTrue(volume.containsKey("Chest"));
            assertTrue(volume.containsKey("Legs"));
            assertTrue(volume.containsKey("Biceps"));
            assertTrue(volume.containsKey("Back/Legs (Posterior Chain)"));
        }

        @Test
        @DisplayName("Falls back to title-cased category for unknown categories")
        void fallsBackForUnknownCategory() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("UNKNOWN_EXERCISE", "some_move", 10, 20000.0)
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");

            // Unknown category → title-cased: "Unknown Exercise"
            assertTrue(volume.containsKey("Unknown Exercise"));
        }

        @Test
        @DisplayName("Volume is sorted descending by value")
        void volumeSortedDescending() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    activeSet("BICEP_CURL", "bicep_curl", 10, 10000.0),     // 100 volume
                    activeSet("BENCH_PRESS", "bench_press", 10, 80000.0),   // 800 volume
                    activeSet("SQUAT", "squat", 10, 100000.0)               // 1000 volume
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");

            // LinkedHashMap preserves insertion order → check descending
            List<Double> values = List.copyOf(volume.values());
            assertEquals(List.of(1000.0, 800.0, 100.0), values);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Handles null exercise sets response gracefully")
        void handlesNullExerciseSets() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(null);

            Map<String, Object> result = service.getLastStrengthWorkout(30);
            assertEquals("success", result.get("status"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<?> sets = (List<?>) data.get("sets");
            assertTrue(sets.isEmpty());
        }

        @Test
        @DisplayName("Handles proxy exception during exercise sets fetch")
        void handlesProxyException() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenThrow(new RuntimeException("Connection refused"));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            // Should still return success with empty sets (exception is caught)
            assertEquals("success", result.get("status"));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<?> sets = (List<?>) data.get("sets");
            assertTrue(sets.isEmpty());
        }

        @Test
        @DisplayName("Sets with no exercises list still get exercise name from category")
        void setsWithEmptyExercisesList() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Test")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of(
                    new ExerciseSetDto("ACTIVE", 10, 50000.0, List.of())
            )));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sets = (List<Map<String, Object>>) data.get("sets");

            assertEquals(1, sets.size());
            assertEquals("Unknown Exercise", sets.get(0).get("exercise"));
        }

        @Test
        @DisplayName("General stats include all expected fields")
        void generalStatsFields() {
            when(proxy.getActivities(0, 30)).thenReturn(List.of(strengthActivity(1, "Chest Day")));
            when(proxy.getExerciseSets(1)).thenReturn(new ExerciseSetsResponse(List.of()));

            Map<String, Object> result = service.getLastStrengthWorkout(30);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");

            assertEquals(1L, data.get("activityId"));
            assertEquals("Chest Day", data.get("activityName"));
            assertEquals("2024-01-15 09:00:00", data.get("startTimeLocal"));
            assertEquals("00:30:00", data.get("duration"));
            assertEquals(1800.0, data.get("durationSeconds"));
            assertEquals(250, data.get("calories"));
            assertEquals(120, data.get("averageHR"));
            assertEquals(155, data.get("maxHR"));
            assertEquals(2.1, data.get("aerobicTrainingEffect"));
            assertEquals(3.5, data.get("anaerobicTrainingEffect"));
        }
    }
}
