package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ActivityDto.ActivityTypeDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseSetDto;
import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutSetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class StrengthWorkoutServiceTest {

    @Mock
    private GarminProxyClient proxy;

    @Mock
    private StrengthWorkoutRepository workoutRepository;

    @Mock
    private StrengthWorkoutSetRepository setRepository;

    @InjectMocks
    private StrengthWorkoutService service;

    private ActivityDto strengthActivity(long id, String name) {
        return new ActivityDto(id, name,
                new ActivityTypeDto("strength_training"),
                "2026-05-27 10:26:04", 1800.0, 250, 94, 135, 0.6, 0.3);
    }

    private ExerciseSetDto activeSet(String category, String name, int reps, double weightGrams) {
        return new ExerciseSetDto("ACTIVE", reps, weightGrams,
                List.of(new ExerciseDto(category, name)));
    }

    @Test
    @DisplayName("syncStrengthWorkouts incrementally saves unsaved workouts")
    void syncStrengthWorkoutsIncrementallySaves() {
        when(proxy.getActivities(0, 10)).thenReturn(List.of(
                strengthActivity(1L, "Session 1"),
                strengthActivity(2L, "Session 2")
        ));
        // Workout 1 already exists, workout 2 is new
        when(workoutRepository.existsByActivityId(1L)).thenReturn(true);
        when(workoutRepository.existsByActivityId(2L)).thenReturn(false);

        when(proxy.getExerciseSets(2L)).thenReturn(new ExerciseSetsResponse(List.of(
                activeSet("BENCH_PRESS", "bench_press", 10, 80000.0) // 80 kg
        )));

        int synced = service.syncStrengthWorkouts(10);

        assertEquals(1, synced);
        verify(workoutRepository, times(1)).save(any(StrengthWorkout.class));
    }

    @Test
    @DisplayName("getLastStrengthWorkout reads latest from DB and calculates volume")
    void getLastStrengthWorkoutReadsFromDb() {
        StrengthWorkout workout = new StrengthWorkout();
        workout.setActivityId(123L);
        workout.setActivityName("Workout Test");
        workout.setWorkoutDate(LocalDate.of(2026, 5, 27));
        workout.setWorkoutTime(LocalTime.of(10, 26, 4));
        workout.setDurationSeconds(1800);
        workout.setCalories(250);

        StrengthWorkoutSet set = new StrengthWorkoutSet(workout, 1, "Bench Press", "Bench Press", 10, BigDecimal.valueOf(80.0));
        set.setId(10L);
        workout.addSet(set);

        when(workoutRepository.findFirstByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(Optional.of(workout));

        Map<String, Object> result = service.getLastStrengthWorkout(30);

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("Workout Test", data.get("activityName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets = (List<Map<String, Object>>) data.get("sets");
        assertEquals(1, sets.size());
        assertEquals(10L, sets.get(0).get("setId"));
        assertEquals("Bench Press", sets.get(0).get("exercise"));
        assertEquals(10, sets.get(0).get("reps"));
        assertEquals(80.0, sets.get(0).get("weightKg"));

        @SuppressWarnings("unchecked")
        Map<String, Double> volume = (Map<String, Double>) data.get("volumeByMuscleGroup");
        assertEquals(800.0, volume.get("Chest"));
    }

    @Test
    @DisplayName("updateSetDetails saves customized name, weightKg, and reps")
    void updateSetDetailsModifiesRecord() {
        StrengthWorkoutSet set = new StrengthWorkoutSet(null, 1, "Bench Press", "Bench Press", 10, BigDecimal.valueOf(80.0));
        set.setId(10L);

        when(setRepository.findById(10L)).thenReturn(Optional.of(set));

        // Update to custom name, new weight, new reps
        service.updateSetDetails(10L, "Panca Piana", 85.5, 12);
        assertEquals("Panca Piana", set.getExerciseName());
        assertEquals(BigDecimal.valueOf(85.5), set.getWeightKg());
        assertEquals(12, set.getReps());
        verify(setRepository, times(1)).save(set);

        // Reset to original name
        service.updateSetDetails(10L, "", 90.0, 8);
        assertEquals("Bench Press", set.getExerciseName());
        assertEquals(BigDecimal.valueOf(90.0), set.getWeightKg());
        assertEquals(8, set.getReps());
    }

    @Test
    @DisplayName("getWorkoutHistory aggregates weekly volume by muscle group")
    void getWorkoutHistoryGroupsByWeek() {
        StrengthWorkout workout = new StrengthWorkout();
        workout.setWorkoutDate(LocalDate.of(2026, 6, 1)); // Monday
        workout.setWorkoutTime(LocalTime.of(10, 0));
        
        StrengthWorkoutSet set = new StrengthWorkoutSet(workout, 1, "Bench Press", "Bench Press", 10, BigDecimal.valueOf(80.0));
        workout.addSet(set);

        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(List.of(workout));

        Map<String, Object> result = service.getWorkoutHistory();
        assertEquals("success", result.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> data = (Map<String, Map<String, Double>>) result.get("data");
        assertTrue(data.containsKey("2026-06-01"));
        assertEquals(800.0, data.get("2026-06-01").get("Chest"));
    }

    @Test
    @DisplayName("getExerciseProgression calculates max weight and 1RM progression curve")
    void getExerciseProgressionPlotsCurve() {
        StrengthWorkout w1 = new StrengthWorkout();
        w1.setWorkoutDate(LocalDate.of(2026, 5, 20));
        w1.setWorkoutTime(LocalTime.of(10, 0));
        // 10 reps @ 80kg -> 1RM = 80 * (1 + 10/30) = 106.7kg
        StrengthWorkoutSet set1 = new StrengthWorkoutSet(w1, 1, "Bench Press", "Bench Press", 10, BigDecimal.valueOf(80.0));
        w1.addSet(set1);

        StrengthWorkout w2 = new StrengthWorkout();
        w2.setWorkoutDate(LocalDate.of(2026, 5, 27));
        w2.setWorkoutTime(LocalTime.of(10, 0));
        // 5 reps @ 90kg -> 1RM = 90 * (1 + 5/30) = 105.0kg
        StrengthWorkoutSet set2 = new StrengthWorkoutSet(w2, 1, "Bench Press", "Panca Piana", 5, BigDecimal.valueOf(90.0));
        w2.addSet(set2);

        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(List.of(w2, w1));

        Map<String, Object> result = service.getExerciseProgression("Panca Piana"); // query by custom name
        assertEquals("success", result.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertEquals(1, data.size(), "Should only match the w2 session where the custom name was 'Panca Piana'");
        assertEquals("2026-05-27", data.get(0).get("date"));
        assertEquals(90.0, data.get(0).get("maxWeightKg"));
        assertEquals(105.0, data.get(0).get("estimated1RM"));

        // Match original name
        Map<String, Object> result2 = service.getExerciseProgression("Bench Press");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data2 = (List<Map<String, Object>>) result2.get("data");
        assertEquals(1, data2.size(), "Should only match the w1 session where the name was 'Bench Press'");
    }
}
