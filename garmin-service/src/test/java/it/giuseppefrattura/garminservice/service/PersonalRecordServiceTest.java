package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.ExercisePersonalRecord;
import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import it.giuseppefrattura.garminservice.repository.ExercisePersonalRecordRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class PersonalRecordServiceTest {

    @Mock
    private ExercisePersonalRecordRepository prRepository;

    @Mock
    private StrengthWorkoutRepository workoutRepository;

    @InjectMocks
    private PersonalRecordService service;

    @Test
    @DisplayName("calculateEstimated1Rm returns accurate Brzycki / Epley estimations")
    void testCalculateEstimated1Rm() {
        assertEquals(0.0, service.calculateEstimated1Rm(0, 10));
        assertEquals(0.0, service.calculateEstimated1Rm(100, 0));
        
        // 1 rep should equal exact weight
        assertEquals(100.0, service.calculateEstimated1Rm(100.0, 1));

        // 6 reps at 70 kg via Brzycki: 70 / (1.0278 - 0.0278 * 6) = 70 / 0.861 = ~81.3 kg
        double est6reps = service.calculateEstimated1Rm(70.0, 6);
        assertTrue(est6reps > 81.0 && est6reps < 82.0, "Expected ~81.3 kg, got: " + est6reps);

        // 10 reps at 60 kg via Brzycki: 60 / (1.0278 - 0.278) = 60 / 0.7498 = ~80.02 kg
        double est10reps = service.calculateEstimated1Rm(60.0, 10);
        assertTrue(est10reps > 79.5 && est10reps < 80.5, "Expected ~80.0 kg, got: " + est10reps);

        // 15 reps at 50 kg via Epley: 50 * (1 + 15/30) = 75.0 kg
        double est15reps = service.calculateEstimated1Rm(50.0, 15);
        assertEquals(75.0, est15reps, 0.01);
    }

    @Test
    @DisplayName("recalculateAllRecords properly evaluates progressive PRs across workouts")
    void testRecalculateAllRecords() {
        StrengthWorkout w1 = new StrengthWorkout();
        w1.setActivityId(101L);
        w1.setWorkoutDate(LocalDate.of(2026, 8, 1));
        w1.setWorkoutTime(LocalTime.of(10, 0));
        
        StrengthWorkoutSet s1 = new StrengthWorkoutSet(w1, 1, "Bench Press", "Panca Piana", "Chest", 10, BigDecimal.valueOf(50.0));
        s1.setId(1L);
        w1.addSet(s1);

        StrengthWorkout w2 = new StrengthWorkout();
        w2.setActivityId(102L);
        w2.setWorkoutDate(LocalDate.of(2026, 8, 8));
        w2.setWorkoutTime(LocalTime.of(10, 0));
        
        // Higher weight and volume
        StrengthWorkoutSet s2 = new StrengthWorkoutSet(w2, 1, "Bench Press", "Panca Piana", "Chest", 6, BigDecimal.valueOf(70.0));
        s2.setId(2L);
        w2.addSet(s2);

        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(List.of(w2, w1));

        Map<String, Object> res = service.recalculateAllRecords();
        assertEquals(1, res.get("distinctExercises"));
        assertTrue((int) res.get("totalPrsSaved") >= 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExercisePersonalRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(prRepository).saveAll(captor.capture());

        List<ExercisePersonalRecord> saved = captor.getValue();
        assertFalse(saved.isEmpty());
        // Verify MAX_WEIGHT progression
        boolean has50kg = saved.stream().anyMatch(r -> "MAX_WEIGHT".equals(r.getRecordType()) && r.getWeightKg() == 50.0);
        boolean has70kg = saved.stream().anyMatch(r -> "MAX_WEIGHT".equals(r.getRecordType()) && r.getWeightKg() == 70.0);
        assertTrue(has50kg);
        assertTrue(has70kg);
    }

    @Test
    @DisplayName("getPrFlagsForActivity correctly indexes PR types by set ID")
    void testGetPrFlagsForActivity() {
        ExercisePersonalRecord pr1 = new ExercisePersonalRecord(
                "Panca Piana", "Chest", "MAX_WEIGHT", 70.0, 70.0, 6, 102L, 2L, LocalDateTime.now()
        );
        ExercisePersonalRecord pr2 = new ExercisePersonalRecord(
                "Panca Piana", "Chest", "MAX_1RM", 81.3, 70.0, 6, 102L, 2L, LocalDateTime.now()
        );

        when(prRepository.findByActivityId(102L)).thenReturn(List.of(pr1, pr2));

        Map<Long, List<String>> flags = service.getPrFlagsForActivity(102L);
        assertEquals(1, flags.size());
        assertTrue(flags.containsKey(2L));
        assertEquals(List.of("MAX_WEIGHT", "MAX_1RM"), flags.get(2L));
    }

    @Test
    @DisplayName("getTrophyRoomCards formats and sorts best records per exercise")
    void testGetTrophyRoomCards() {
        ExercisePersonalRecord pr1 = new ExercisePersonalRecord(
                "Panca Piana", "Chest", "MAX_WEIGHT", 70.0, 70.0, 6, 102L, 2L, LocalDateTime.of(2026, 8, 8, 10, 0)
        );
        ExercisePersonalRecord pr2 = new ExercisePersonalRecord(
                "Panca Piana", "Chest", "MAX_1RM", 81.3, 70.0, 6, 102L, 2L, LocalDateTime.of(2026, 8, 8, 10, 0)
        );

        when(prRepository.findAllOrdered()).thenReturn(List.of(pr1, pr2));

        List<PersonalRecordService.TrophyCardDTO> cards = service.getTrophyRoomCards();
        assertEquals(1, cards.size());
        PersonalRecordService.TrophyCardDTO card = cards.get(0);
        assertEquals("Panca Piana", card.getExerciseName());
        assertEquals("Chest", card.getMuscleGroup());
        assertEquals(70.0, card.getMaxWeightKg());
        assertEquals(81.3, card.getEstimated1RmKg());
        assertEquals("08/08/2026", card.getEstimated1RmDate());
    }
}
