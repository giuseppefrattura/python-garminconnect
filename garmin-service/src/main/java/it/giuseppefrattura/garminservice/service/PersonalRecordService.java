package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.ExercisePersonalRecord;
import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import it.giuseppefrattura.garminservice.repository.ExercisePersonalRecordRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PersonalRecordService {

    private static final Logger log = LoggerFactory.getLogger(PersonalRecordService.class);

    private final ExercisePersonalRecordRepository prRepository;
    private final StrengthWorkoutRepository workoutRepository;

    public PersonalRecordService(ExercisePersonalRecordRepository prRepository,
                                 StrengthWorkoutRepository workoutRepository) {
        this.prRepository = prRepository;
        this.workoutRepository = workoutRepository;
    }

    /**
     * Calculate estimated 1RM using Brzycki (<=10 reps) or Epley (>10 reps).
     */
    public double calculateEstimated1Rm(double weightKg, int reps) {
        if (weightKg <= 0 || reps <= 0) {
            return 0.0;
        }
        if (reps == 1) {
            return weightKg;
        }
        if (reps <= 10) {
            double denom = 1.0278 - (0.0278 * reps);
            return denom > 0 ? (weightKg / denom) : weightKg;
        }
        return weightKg * (1.0 + (reps / 30.0));
    }

    /**
     * Recalculates all Personal Records from the beginning of history.
     */
    @Transactional
    public Map<String, Object> recalculateAllRecords() {
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
            if (workout.getSets() == null || workout.getSets().isEmpty()) {
                continue;
            }

            LocalDateTime achievedAt = workout.getWorkoutDate().atTime(workout.getWorkoutTime());

            for (StrengthWorkoutSet set : workout.getSets()) {
                String exercise = (set.getExerciseName() != null && !set.getExerciseName().isBlank())
                        ? set.getExerciseName().trim()
                        : (set.getOriginalExerciseName() != null ? set.getOriginalExerciseName().trim() : "Unknown");

                if ("Unknown".equalsIgnoreCase(exercise) || exercise.isBlank()) {
                    continue;
                }

                double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
                int reps = set.getReps() != null ? set.getReps() : 0;
                String muscleGroup = set.getMuscleGroup() != null ? set.getMuscleGroup() : "Altro";

                if (weight <= 0 && reps <= 0) {
                    continue;
                }

                String exerciseKey = exercise.toLowerCase();

                // 1. MAX WEIGHT
                if (weight > 0) {
                    double currentMaxW = runningMaxWeight.getOrDefault(exerciseKey, 0.0);
                    if (weight > currentMaxW) {
                        runningMaxWeight.put(exerciseKey, weight);
                        newRecords.add(new ExercisePersonalRecord(
                                exercise, muscleGroup, "MAX_WEIGHT", weight, weight, reps,
                                workout.getActivityId(), set.getId(), achievedAt
                        ));
                    }
                }

                // 2. MAX ESTIMATED 1RM
                if (weight > 0 && reps > 0) {
                    double est1Rm = Math.round(calculateEstimated1Rm(weight, reps) * 10.0) / 10.0;
                    double currentMax1Rm = runningMax1Rm.getOrDefault(exerciseKey, 0.0);
                    if (est1Rm > currentMax1Rm) {
                        runningMax1Rm.put(exerciseKey, est1Rm);
                        newRecords.add(new ExercisePersonalRecord(
                                exercise, muscleGroup, "MAX_1RM", est1Rm, weight, reps,
                                workout.getActivityId(), set.getId(), achievedAt
                        ));
                    }
                }

                // 3. MAX VOLUME SET (reps * weight)
                if (weight > 0 && reps > 0) {
                    double volume = Math.round((weight * reps) * 10.0) / 10.0;
                    double currentMaxVol = runningMaxVolume.getOrDefault(exerciseKey, 0.0);
                    if (volume > currentMaxVol) {
                        runningMaxVolume.put(exerciseKey, volume);
                        newRecords.add(new ExercisePersonalRecord(
                                exercise, muscleGroup, "MAX_VOLUME_SET", volume, weight, reps,
                                workout.getActivityId(), set.getId(), achievedAt
                        ));
                    }
                }
            }
        }

        prRepository.saveAll(newRecords);
        log.info("Finished recalculation: saved {} personal record events across {} exercises",
                newRecords.size(), runningMax1Rm.size());

        Map<String, Object> result = new HashMap<>();
        result.put("totalPrsSaved", newRecords.size());
        result.put("distinctExercises", runningMax1Rm.size());
        return result;
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
            // Auto-trigger recalculation if table is empty
            recalculateAllRecords();
            allRecords = prRepository.findAllOrdered();
        }

        // Group by exercise name (case insensitive)
        Map<String, List<ExercisePersonalRecord>> byExercise = allRecords.stream()
                .collect(Collectors.groupingBy(r -> r.getExerciseName().toLowerCase(), LinkedHashMap::new, Collectors.toList()));

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        List<TrophyCardDTO> cards = new ArrayList<>();

        for (List<ExercisePersonalRecord> records : byExercise.values()) {
            if (records.isEmpty()) continue;

            String exerciseName = records.get(0).getExerciseName();
            String muscleGroup = records.stream()
                    .map(ExercisePersonalRecord::getMuscleGroup)
                    .filter(Objects::nonNull)
                    .findFirst().orElse("Altro");

            TrophyCardDTO card = new TrophyCardDTO(exerciseName, muscleGroup);

            // Find current best for each type
            records.stream()
                    .filter(r -> "MAX_WEIGHT".equals(r.getRecordType()))
                    .max(Comparator.comparingDouble(ExercisePersonalRecord::getRecordValue))
                    .ifPresent(r -> {
                        card.setMaxWeightKg(r.getWeightKg());
                        card.setMaxWeightReps(r.getReps());
                        card.setMaxWeightDate(r.getAchievedAt().format(dtf));
                    });

            records.stream()
                    .filter(r -> "MAX_1RM".equals(r.getRecordType()))
                    .max(Comparator.comparingDouble(ExercisePersonalRecord::getRecordValue))
                    .ifPresent(r -> {
                        card.setEstimated1RmKg(r.getRecordValue());
                        card.setEst1RmWeightKg(r.getWeightKg());
                        card.setEst1RmReps(r.getReps());
                        card.setEstimated1RmDate(r.getAchievedAt().format(dtf));
                    });

            records.stream()
                    .filter(r -> "MAX_VOLUME_SET".equals(r.getRecordType()))
                    .max(Comparator.comparingDouble(ExercisePersonalRecord::getRecordValue))
                    .ifPresent(r -> {
                        card.setMaxVolumeKg(r.getRecordValue());
                        card.setMaxVolumeWeightKg(r.getWeightKg());
                        card.setMaxVolumeReps(r.getReps());
                        card.setMaxVolumeDate(r.getAchievedAt().format(dtf));
                    });

            card.setTotalPrCount(records.size());
            cards.add(card);
        }

        // Sort by muscle group, then by 1RM / Weight desc
        cards.sort((a, b) -> {
            int comp = a.getMuscleGroup().compareToIgnoreCase(b.getMuscleGroup());
            if (comp != 0) return comp;
            double aVal = a.getEstimated1RmKg() != null ? a.getEstimated1RmKg() : (a.getMaxWeightKg() != null ? a.getMaxWeightKg() : 0);
            double bVal = b.getEstimated1RmKg() != null ? b.getEstimated1RmKg() : (b.getMaxWeightKg() != null ? b.getMaxWeightKg() : 0);
            return Double.compare(bVal, aVal);
        });

        return cards;
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
