package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.dto.RecalculationResult;
import it.giuseppefrattura.garminservice.model.ExercisePersonalRecord;
import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import it.giuseppefrattura.garminservice.repository.ExercisePersonalRecordRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import it.giuseppefrattura.garminservice.util.OneRepMaxCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class PersonalRecordService {

    private static final Logger log = LoggerFactory.getLogger(PersonalRecordService.class);
    private static final String DEFAULT_MUSCLE_GROUP = "Altro";
    private static final String RECORD_MAX_WEIGHT = "MAX_WEIGHT";
    private static final String RECORD_MAX_1RM = "MAX_1RM";
    private static final String RECORD_MAX_VOLUME_SET = "MAX_VOLUME_SET";

    private final ExercisePersonalRecordRepository prRepository;
    private final StrengthWorkoutRepository workoutRepository;

    public PersonalRecordService(ExercisePersonalRecordRepository prRepository,
                                 StrengthWorkoutRepository workoutRepository) {
        this.prRepository = prRepository;
        this.workoutRepository = workoutRepository;
    }

    /**
     * Recalculates all Personal Records from the beginning of history.
     */
    @Transactional
    public RecalculationResult recalculateAllRecords() {
        return performRecalculation();
    }

    private RecalculationResult performRecalculation() {
        log.info("Starting complete recalculation of personal records across all workouts");
        prRepository.deleteAll();

        List<StrengthWorkout> workouts = new ArrayList<>(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc());
        // Reverse to process chronologically (oldest to newest)
        Collections.reverse(workouts);

        Map<String, Double> runningMaxWeight = new HashMap<>();
        Map<String, Double> runningMax1Rm = new HashMap<>();
        Map<String, Double> runningMaxVolume = new HashMap<>();
        List<ExercisePersonalRecord> newRecords = new ArrayList<>();

        for (StrengthWorkout workout : workouts) {
            processWorkoutForRecords(workout, runningMaxWeight, runningMax1Rm, runningMaxVolume, newRecords);
        }

        prRepository.saveAll(newRecords);
        log.info("Finished recalculation: saved {} personal record events across {} exercises",
                newRecords.size(), runningMax1Rm.size());

        return new RecalculationResult(newRecords.size(), runningMax1Rm.size());
    }

    private void processWorkoutForRecords(StrengthWorkout workout,
                                          Map<String, Double> runningMaxWeight,
                                          Map<String, Double> runningMax1Rm,
                                          Map<String, Double> runningMaxVolume,
                                          List<ExercisePersonalRecord> newRecords) {
        if (workout.getSets() == null || workout.getSets().isEmpty()) {
            return;
        }
        LocalDateTime achievedAt = workout.getWorkoutDate().atTime(workout.getWorkoutTime());
        for (StrengthWorkoutSet set : workout.getSets()) {
            processSingleSet(workout, set, achievedAt, runningMaxWeight, runningMax1Rm, runningMaxVolume, newRecords);
        }
    }

    private void processSingleSet(StrengthWorkout workout,
                                  StrengthWorkoutSet set,
                                  LocalDateTime achievedAt,
                                  Map<String, Double> runningMaxWeight,
                                  Map<String, Double> runningMax1Rm,
                                  Map<String, Double> runningMaxVolume,
                                  List<ExercisePersonalRecord> newRecords) {
        String exercise = resolveExerciseName(set);
        if ("Unknown".equalsIgnoreCase(exercise) || exercise.isBlank()) {
            return;
        }

        double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
        int reps = set.getReps() != null ? set.getReps() : 0;
        if (weight <= 0) {
            return;
        }

        String muscleGroup = set.getMuscleGroup() != null ? set.getMuscleGroup() : DEFAULT_MUSCLE_GROUP;
        String exerciseKey = exercise.toLowerCase();

        recordIfGreater(runningMaxWeight, exerciseKey, weight, exercise, muscleGroup,
                RECORD_MAX_WEIGHT, weight, reps, workout.getActivityId(), set.getId(), achievedAt, newRecords);

        if (reps > 0) {
            double est1Rm = Math.round(OneRepMaxCalculator.estimate1Rm(weight, reps) * 10.0) / 10.0;
            recordIfGreater(runningMax1Rm, exerciseKey, est1Rm, exercise, muscleGroup,
                    RECORD_MAX_1RM, weight, reps, workout.getActivityId(), set.getId(), achievedAt, newRecords);

            double volume = Math.round((weight * reps) * 10.0) / 10.0;
            recordIfGreater(runningMaxVolume, exerciseKey, volume, exercise, muscleGroup,
                    RECORD_MAX_VOLUME_SET, weight, reps, workout.getActivityId(), set.getId(), achievedAt, newRecords);
        }
    }

    private String resolveExerciseName(StrengthWorkoutSet set) {
        if (set.getExerciseName() != null && !set.getExerciseName().isBlank()) {
            return set.getExerciseName().trim();
        }
        return set.getOriginalExerciseName() != null ? set.getOriginalExerciseName().trim() : "Unknown";
    }

    private void recordIfGreater(Map<String, Double> runningMax,
                                 String exerciseKey,
                                 double candidateValue,
                                 String exercise,
                                 String muscleGroup,
                                 String recordType,
                                 double weight,
                                 int reps,
                                 Long activityId,
                                 Long setId,
                                 LocalDateTime achievedAt,
                                 List<ExercisePersonalRecord> newRecords) {
        double currentMax = runningMax.getOrDefault(exerciseKey, 0.0);
        if (candidateValue > currentMax) {
            runningMax.put(exerciseKey, candidateValue);
            newRecords.add(new ExercisePersonalRecord(
                    exercise, muscleGroup, recordType, candidateValue, weight, reps,
                    activityId, setId, achievedAt
            ));
        }
    }

    /**
     * Retrieves PR flags for each set in a specific activity.
     * Returns a map: setId -> list of record types (e.g. ["MAX_WEIGHT", "MAX_1RM"]).
     */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> getPrFlagsForActivity(Long activityId) {
        List<ExercisePersonalRecord> records = prRepository.findByActivityId(activityId);
        Map<Long, List<String>> map = new HashMap<>();
        for (ExercisePersonalRecord r : records) {
            if (r.getSetId() != null) {
                map.computeIfAbsent(r.getSetId(), k -> new ArrayList<>()).add(r.getRecordType());
            }
        }
        return map;
    }

    /**
     * Get summary cards for the Trophy Room grouped by muscle group and exercise.
     */
    @Transactional
    public List<TrophyCardDTO> getTrophyRoomCards() {
        List<ExercisePersonalRecord> allRecords = prRepository.findAllOrdered();
        if (allRecords.isEmpty()) {
            // Auto-trigger recalculation within the current transaction if table is empty
            performRecalculation();
            allRecords = prRepository.findAllOrdered();
        }

        Map<String, List<ExercisePersonalRecord>> byExercise = allRecords.stream()
                .collect(Collectors.groupingBy(r -> r.getExerciseName().toLowerCase(), LinkedHashMap::new, Collectors.toList()));

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<TrophyCardDTO> cards = new ArrayList<>();

        for (List<ExercisePersonalRecord> records : byExercise.values()) {
            if (!records.isEmpty()) {
                cards.add(buildTrophyCard(records, dtf));
            }
        }

        cards.sort(PersonalRecordService::compareTrophyCards);
        return cards;
    }

    private TrophyCardDTO buildTrophyCard(List<ExercisePersonalRecord> records, DateTimeFormatter dtf) {
        String exerciseName = records.get(0).getExerciseName();
        String muscleGroup = records.stream()
                .map(ExercisePersonalRecord::getMuscleGroup)
                .filter(Objects::nonNull)
                .findFirst().orElse(DEFAULT_MUSCLE_GROUP);

        TrophyCardDTO card = new TrophyCardDTO(exerciseName, muscleGroup);

        findBestByType(records, RECORD_MAX_WEIGHT).ifPresent(r -> {
            card.setMaxWeightKg(r.getWeightKg());
            card.setMaxWeightReps(r.getReps());
            card.setMaxWeightDate(r.getAchievedAt().format(dtf));
        });

        findBestByType(records, RECORD_MAX_1RM).ifPresent(r -> {
            card.setEstimated1RmKg(r.getRecordValue());
            card.setEst1RmWeightKg(r.getWeightKg());
            card.setEst1RmReps(r.getReps());
            card.setEstimated1RmDate(r.getAchievedAt().format(dtf));
        });

        findBestByType(records, RECORD_MAX_VOLUME_SET).ifPresent(r -> {
            card.setMaxVolumeKg(r.getRecordValue());
            card.setMaxVolumeWeightKg(r.getWeightKg());
            card.setMaxVolumeReps(r.getReps());
            card.setMaxVolumeDate(r.getAchievedAt().format(dtf));
        });

        card.setTotalPrCount(records.size());
        return card;
    }

    private java.util.Optional<ExercisePersonalRecord> findBestByType(List<ExercisePersonalRecord> records, String recordType) {
        return records.stream()
                .filter(r -> recordType.equals(r.getRecordType()))
                .max(Comparator.comparingDouble(ExercisePersonalRecord::getRecordValue));
    }

    private static int compareTrophyCards(TrophyCardDTO a, TrophyCardDTO b) {
        int comp = a.getMuscleGroup().compareToIgnoreCase(b.getMuscleGroup());
        if (comp != 0) {
            return comp;
        }
        return Double.compare(primaryScore(b), primaryScore(a));
    }

    private static double primaryScore(TrophyCardDTO card) {
        if (card.getEstimated1RmKg() != null) {
            return card.getEstimated1RmKg();
        }
        return card.getMaxWeightKg() != null ? card.getMaxWeightKg() : 0.0;
    }

    /**
     * Get the progression timeline for an exercise.
     */
    @Transactional(readOnly = true)
    public List<ExercisePersonalRecord> getExerciseTimeline(String exerciseName) {
        if (exerciseName == null || exerciseName.isBlank()) {
            return Collections.emptyList();
        }
        return prRepository.findByExerciseNameOrderByAchievedAtAsc(exerciseName);
    }

    // DTO for Trophy Room UI Card
    public static class TrophyCardDTO {
        private String exerciseName;
        private String muscleGroup;
        private Double maxWeightKg;
        private Integer maxWeightReps;
        private String maxWeightDate;
        private Double estimated1RmKg;
        private Double est1RmWeightKg;
        private Integer est1RmReps;
        private String estimated1RmDate;
        private Double maxVolumeKg;
        private Double maxVolumeWeightKg;
        private Integer maxVolumeReps;
        private String maxVolumeDate;
        private int totalPrCount;

        public TrophyCardDTO(String exerciseName, String muscleGroup) {
            this.exerciseName = exerciseName;
            this.muscleGroup = muscleGroup;
        }

        public String getExerciseName() { return exerciseName; }
        public String getMuscleGroup() { return muscleGroup; }
        public Double getMaxWeightKg() { return maxWeightKg; }
        public void setMaxWeightKg(Double maxWeightKg) { this.maxWeightKg = maxWeightKg; }
        public Integer getMaxWeightReps() { return maxWeightReps; }
        public void setMaxWeightReps(Integer maxWeightReps) { this.maxWeightReps = maxWeightReps; }
        public String getMaxWeightDate() { return maxWeightDate; }
        public void setMaxWeightDate(String maxWeightDate) { this.maxWeightDate = maxWeightDate; }
        public Double getEstimated1RmKg() { return estimated1RmKg; }
        public void setEstimated1RmKg(Double estimated1RmKg) { this.estimated1RmKg = estimated1RmKg; }
        public Double getEst1RmWeightKg() { return est1RmWeightKg; }
        public void setEst1RmWeightKg(Double est1RmWeightKg) { this.est1RmWeightKg = est1RmWeightKg; }
        public Integer getEst1RmReps() { return est1RmReps; }
        public void setEst1RmReps(Integer est1RmReps) { this.est1RmReps = est1RmReps; }
        public String getEstimated1RmDate() { return estimated1RmDate; }
        public void setEstimated1RmDate(String estimated1RmDate) { this.estimated1RmDate = estimated1RmDate; }
        public Double getMaxVolumeKg() { return maxVolumeKg; }
        public void setMaxVolumeKg(Double maxVolumeKg) { this.maxVolumeKg = maxVolumeKg; }
        public Double getMaxVolumeWeightKg() { return maxVolumeWeightKg; }
        public void setMaxVolumeWeightKg(Double maxVolumeWeightKg) { this.maxVolumeWeightKg = maxVolumeWeightKg; }
        public Integer getMaxVolumeReps() { return maxVolumeReps; }
        public void setMaxVolumeReps(Integer maxVolumeReps) { this.maxVolumeReps = maxVolumeReps; }
        public String getMaxVolumeDate() { return maxVolumeDate; }
        public void setMaxVolumeDate(String maxVolumeDate) { this.maxVolumeDate = maxVolumeDate; }
        public int getTotalPrCount() { return totalPrCount; }
        public void setTotalPrCount(int totalPrCount) { this.totalPrCount = totalPrCount; }
    }
}
