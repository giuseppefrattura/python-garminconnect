package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ActivityDto.ActivityTypeDto;
import it.giuseppefrattura.garminservice.dto.ApiResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseSetDto;
import it.giuseppefrattura.garminservice.dto.LastStrengthWorkoutDto;
import it.giuseppefrattura.garminservice.dto.ProgressionPointDto;
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

    @Mock
    private PersonalRecordService personalRecordService;

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

        ApiResponse<LastStrengthWorkoutDto> result = service.getLastStrengthWorkout();

        assertEquals("success", result.status());
        LastStrengthWorkoutDto data = result.data();
        assertEquals("Workout Test", data.activityName());
        assertEquals("2026-05-27 10:26:04", data.startTimeLocal());
        assertEquals("00:30:00", data.duration());

        assertEquals(1, data.sets().size());
        LastStrengthWorkoutDto.WorkoutSetDto setDto = data.sets().get(0);
        assertEquals(10L, setDto.setId());
        assertEquals("Bench Press", setDto.exercise());
        assertEquals(10, setDto.reps());
        assertEquals(80.0, setDto.weightKg());
        assertFalse(setDto.isPr());
        assertTrue(setDto.prTypes().isEmpty());

        assertEquals(800.0, data.volumeByMuscleGroup().get("Chest"));
    }

    @Test
    @DisplayName("getLastStrengthWorkout returns typed error when database is empty")
    void getLastStrengthWorkoutReturnsErrorWhenEmpty() {
        when(workoutRepository.findFirstByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(Optional.empty());

        ApiResponse<LastStrengthWorkoutDto> result = service.getLastStrengthWorkout();

        assertTrue(result.isError());
        assertNull(result.data());
        assertNotNull(result.detail());
    }

    @Test
    @DisplayName("updateSetDetails saves customized name, muscleGroup, weightKg, reps and applies batch update")
    void updateSetDetailsModifiesRecord() {
        StrengthWorkout workout = new StrengthWorkout();
        StrengthWorkoutSet set1 = new StrengthWorkoutSet(workout, 1, "Unknown", "Unknown", 10, BigDecimal.valueOf(80.0));
        set1.setId(10L);
        StrengthWorkoutSet set2 = new StrengthWorkoutSet(workout, 2, "Unknown", "Unknown", 10, BigDecimal.valueOf(80.0));
        set2.setId(11L);
        workout.addSet(set1);
        workout.addSet(set2);

        when(setRepository.findById(10L)).thenReturn(Optional.of(set1));

        // Update single set with custom name and explicit muscle group
        service.updateSetDetails(10L, "Panca Piana", "Chest", 85.5, 12, false);
        assertEquals("Panca Piana", set1.getExerciseName());
        assertEquals("Chest", set1.getMuscleGroup());
        assertEquals(BigDecimal.valueOf(85.5), set1.getWeightKg());
        assertEquals(12, set1.getReps());
        verify(setRepository, times(1)).save(set1);

        // Batch update all matching sets
        service.updateSetDetails(10L, "Panca Piana", "Chest", 90.0, 8, true);
        assertEquals("Panca Piana", set2.getExerciseName());
        assertEquals("Chest", set2.getMuscleGroup());
        assertEquals(BigDecimal.valueOf(90.0), set1.getWeightKg());
        assertEquals(8, set1.getReps());
    }

    @Test
    @DisplayName("resolveMuscleGroup properly detects muscle group from custom name or explicit group")
    void resolveMuscleGroupInfersCorrectly() {
        StrengthWorkoutSet set1 = new StrengthWorkoutSet(null, 1, "Unknown", "Lat Machine", null, 10, BigDecimal.valueOf(60.0));
        assertEquals("Back", service.resolveMuscleGroup(set1));

        StrengthWorkoutSet set2 = new StrengthWorkoutSet(null, 2, "Unknown", "Squat", null, 10, BigDecimal.valueOf(100.0));
        assertEquals("Legs", service.resolveMuscleGroup(set2));

        StrengthWorkoutSet set3 = new StrengthWorkoutSet(null, 3, "Unknown", "Military Press", null, 10, BigDecimal.valueOf(40.0));
        assertEquals("Shoulders", service.resolveMuscleGroup(set3));

        // Explicit override
        StrengthWorkoutSet set4 = new StrengthWorkoutSet(null, 4, "Unknown", "Dip", "Triceps", 10, BigDecimal.valueOf(20.0));
        assertEquals("Triceps", service.resolveMuscleGroup(set4));
    }

    @Test
    @DisplayName("getWorkoutHistory aggregates weekly volume by muscle group")
    void getWorkoutHistoryGroupsByWeek() {
        StrengthWorkout workout = new StrengthWorkout();
        workout.setWorkoutDate(LocalDate.of(2026, 6, 1)); // Monday
        workout.setWorkoutTime(LocalTime.of(10, 0));
        
        StrengthWorkoutSet set = new StrengthWorkoutSet(workout, 1, "Unknown", "Panca Piana", "Chest", 10, BigDecimal.valueOf(80.0));
        workout.addSet(set);

        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(List.of(workout));

        ApiResponse<Map<String, Map<String, Double>>> result = service.getWorkoutHistory();
        assertEquals("success", result.status());

        Map<String, Map<String, Double>> data = result.data();
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
        // 5 reps @ 90kg -> 1RM via Brzycki = 90 / (1.0278 - 0.0278*5) = 90 / 0.8888 = ~101.3kg
        StrengthWorkoutSet set2 = new StrengthWorkoutSet(w2, 1, "Bench Press", "Panca Piana", 5, BigDecimal.valueOf(90.0));
        w2.addSet(set2);

        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(List.of(w2, w1));

        ApiResponse<List<ProgressionPointDto>> result = service.getExerciseProgression("Panca Piana"); // query by custom name
        assertEquals("success", result.status());

        List<ProgressionPointDto> data = result.data();
        assertEquals(1, data.size(), "Should only match the w2 session where the custom name was 'Panca Piana'");
        assertEquals("2026-05-27", data.get(0).date());
        assertEquals(90.0, data.get(0).maxWeightKg());
        assertEquals(101.3, data.get(0).estimated1RM());

        // Match original name
        ApiResponse<List<ProgressionPointDto>> result2 = service.getExerciseProgression("Bench Press");
        assertEquals(1, result2.data().size(), "Should only match the w1 session where the name was 'Bench Press'");
    }

    @Test
    @DisplayName("getExerciseProgression returns typed error for blank exercise name")
    void getExerciseProgressionReturnsErrorForBlankName() {
        ApiResponse<List<ProgressionPointDto>> result = service.getExerciseProgression("   ");

        assertTrue(result.isError());
        assertNull(result.data());
        assertNotNull(result.detail());
        verifyNoInteractions(workoutRepository);
    }
}
