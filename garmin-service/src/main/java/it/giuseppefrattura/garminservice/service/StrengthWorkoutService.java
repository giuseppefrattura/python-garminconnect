package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseSetDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseDto;
import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutSetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Business logic for strength/weight training analysis.
 * <p>
 * Synchronizes workout history from Garmin, persists sessions and individual sets in PostgreSQL,
 * and compiles historical training volume and exercise progression analytics.
 */
@Service
public class StrengthWorkoutService {

    private static final Logger log = LoggerFactory.getLogger(StrengthWorkoutService.class);

    private final GarminProxyClient proxy;
    private final StrengthWorkoutRepository workoutRepository;
    private final StrengthWorkoutSetRepository setRepository;

    /**
     * Mapping from Garmin exercise categories to human-readable muscle groups.
     */
    private static final Map<String, String> MUSCLE_GROUP_MAP = Map.ofEntries(
            Map.entry("BENCH_PRESS", "Chest"),
            Map.entry("CHEST_FLY", "Chest"),
            Map.entry("PUSH_UP", "Chest"),
            Map.entry("PULL_UP", "Back"),
            Map.entry("ROW", "Back"),
            Map.entry("LAT_PULLDOWN", "Back"),
            Map.entry("DEADLIFT", "Back/Legs (Posterior Chain)"),
            Map.entry("SQUAT", "Legs"),
            Map.entry("LUNGE", "Legs"),
            Map.entry("LEG_PRESS", "Legs"),
            Map.entry("LEG_CURL", "Legs"),
            Map.entry("LEG_EXTENSION", "Legs"),
            Map.entry("CALF_RAISE", "Calves"),
            Map.entry("SHOULDER_PRESS", "Shoulders"),
            Map.entry("LATERAL_RAISE", "Shoulders"),
            Map.entry("FRONT_RAISE", "Shoulders"),
            Map.entry("BICEP_CURL", "Biceps"),
            Map.entry("TRICEPS_EXTENSION", "Triceps"),
            Map.entry("CRUNCH", "Core"),
            Map.entry("SIT_UP", "Core"),
            Map.entry("PLANK", "Core"),
            Map.entry("CORE", "Core"),
            Map.entry("OLYMPIC_LIFT", "Full Body (Olympic)"),
            Map.entry("CARRY", "Full Body/Core"),
            Map.entry("FARMERS_WALK", "Full Body/Core")
    );

    public StrengthWorkoutService(
            GarminProxyClient proxy,
            StrengthWorkoutRepository workoutRepository,
            StrengthWorkoutSetRepository setRepository) {
        this.proxy = proxy;
        this.workoutRepository = workoutRepository;
        this.setRepository = setRepository;
    }

    /**
     * Synchronize strength workouts from the Garmin proxy into the PostgreSQL database.
     * Checks if a session has already been saved; if not, fetches the set breakdown.
     *
     * @param limit maximum number of recent activities to scan
     * @return count of newly synchronized workouts
     */
    public int syncStrengthWorkouts(int limit) {
        log.info("Starting strength workouts synchronization (limit={})", limit);
        List<ActivityDto> activities = proxy.getActivities(0, limit);
        int savedCount = 0;

        List<ActivityDto> strengthActivities = activities.stream()
                .filter(a -> {
                    String typeKey = a.activityType() != null ? a.activityType().typeKey() : "";
                    return typeKey != null
                            && (typeKey.toLowerCase().contains("strength")
                            || typeKey.toLowerCase().contains("training"));
                })
                .toList();

        for (ActivityDto act : strengthActivities) {
            long activityId = act.activityId();

            if (workoutRepository.existsByActivityId(activityId)) {
                log.debug("Strength workout {} already exists in database, skipping", activityId);
                continue;
            }

            log.info("Syncing new strength workout: {} (ID: {})", act.activityName(), activityId);

            StrengthWorkout workout = new StrengthWorkout();
            workout.setActivityId(activityId);
            workout.setActivityName(act.activityName() != null ? act.activityName() : "Unknown");
            
            if (act.startTimeLocal() != null && act.startTimeLocal().length() >= 19) {
                try {
                    String startStr = act.startTimeLocal();
                    LocalDate date = LocalDate.parse(startStr.substring(0, 10));
                    LocalTime time = LocalTime.parse(startStr.substring(11, 19));
                    workout.setWorkoutDate(date);
                    workout.setWorkoutTime(time);
                } catch (Exception e) {
                    log.warn("Could not parse start time '{}': {}", act.startTimeLocal(), e.getMessage());
                    workout.setWorkoutDate(LocalDate.now());
                    workout.setWorkoutTime(LocalTime.MIDNIGHT);
                }
            } else {
                workout.setWorkoutDate(LocalDate.now());
                workout.setWorkoutTime(LocalTime.MIDNIGHT);
            }

            workout.setDurationSeconds(act.duration() != null ? act.duration().intValue() : 0);
            workout.setCalories(act.calories() != null ? act.calories() : 0);
            workout.setAverageHr(act.averageHR());
            workout.setMaxHr(act.maxHR());
            workout.setAerobicTe(act.aerobicTrainingEffect() != null ? BigDecimal.valueOf(act.aerobicTrainingEffect()) : null);
            workout.setAnaerobicTe(act.anaerobicTrainingEffect() != null ? BigDecimal.valueOf(act.anaerobicTrainingEffect()) : null);

            try {
                ExerciseSetsResponse setsResponse = proxy.getExerciseSets(activityId);
                if (setsResponse != null && setsResponse.exerciseSets() != null) {
                    int setNum = 1;
                    for (ExerciseSetDto exSet : setsResponse.exerciseSets()) {
                        if ("REST".equals(exSet.setType())) continue;

                        String exName = "Unknown Exercise";
                        String category = null;

                        if (exSet.exercises() != null && !exSet.exercises().isEmpty()) {
                            ExerciseDto first = exSet.exercises().get(0);
                            category = first.category();
                            if (first.name() != null && !first.name().isBlank()) {
                                exName = toTitleCase(first.name().replace("_", " "));
                            } else if (category != null && !category.isBlank()) {
                                exName = toTitleCase(category.replace("_", " "));
                            }
                        }

                        int reps = exSet.repetitionCount() != null ? exSet.repetitionCount() : 0;
                        double rawWeight = exSet.weight() != null ? exSet.weight() : 0.0;
                        double weightKg = rawWeight > 0 ? rawWeight / 1000.0 : 0.0;

                        if (reps > 0 || weightKg > 0 || !"Unknown Exercise".equals(exName)) {
                            StrengthWorkoutSet setEntry = new StrengthWorkoutSet();
                            setEntry.setSetNumber(setNum++);
                            setEntry.setOriginalExerciseName(exName);
                            setEntry.setExerciseName(exName);
                            setEntry.setMuscleGroup(resolveMuscleGroup(exName, exName, null));
                            setEntry.setReps(reps);
                            setEntry.setWeightKg(BigDecimal.valueOf(Math.round(weightKg * 10.0) / 10.0));
                            workout.addSet(setEntry);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Could not fetch exercise sets for activity during sync {}: {}", activityId, ex.getMessage());
            }

            workoutRepository.save(workout);
            savedCount++;
        }

        log.info("Finished strength workouts synchronization. Saved {} new workouts.", savedCount);
        return savedCount;
    }

    /**
     * Resolve target muscle group for a given set, following priority:
     * 1. Explicit muscleGroup stored on the set
     * 2. Inferred from current (custom) exerciseName
     * 3. Inferred from originalExerciseName
     * 4. Garmin Category mapping
     * 5. "Altro" (fallback)
     */
    public String resolveMuscleGroup(StrengthWorkoutSet set) {
        if (set == null) return "Altro";
        return resolveMuscleGroup(set.getExerciseName(), set.getOriginalExerciseName(), set.getMuscleGroup());
    }

    public String resolveMuscleGroup(String customName, String originalName, String explicitMuscleGroup) {
        if (explicitMuscleGroup != null && !explicitMuscleGroup.isBlank()) {
            return explicitMuscleGroup.trim();
        }

        if (customName != null && !customName.isBlank()) {
            String inferred = inferMuscleGroupFromName(customName);
            if (inferred != null) {
                return inferred;
            }
        }

        if (originalName != null && !originalName.isBlank()) {
            String inferred = inferMuscleGroupFromName(originalName);
            if (inferred != null) {
                return inferred;
            }
            String category = originalName.toUpperCase().replace(" ", "_");
            if (MUSCLE_GROUP_MAP.containsKey(category)) {
                return MUSCLE_GROUP_MAP.get(category);
            }
        }

        return "Altro";
    }

    private String inferMuscleGroupFromName(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        if (lower.contains("panca") || lower.contains("bench") || lower.contains("petto") || lower.contains("chest") 
                || lower.contains("croci") || lower.contains("push up") || lower.contains("push-up") 
                || lower.contains("dip") || lower.contains("spinte")) {
            return "Chest";
        }
        if (lower.contains("lat machine") || lower.contains("lat pull") || lower.contains("lat_pull") 
                || lower.contains("trazioni") || lower.contains("pull up") || lower.contains("pull-up") 
                || lower.contains("rematore") || lower.contains("row") || lower.contains("pulley") 
                || lower.contains("dorso") || lower.contains("back") || lower.contains("stacco") 
                || lower.contains("deadlift") || lower.contains("shrug")) {
            return "Back";
        }
        if (lower.contains("spalle") || lower.contains("shoulder") || lower.contains("military") 
                || lower.contains("lento") || lower.contains("alzate") || lower.contains("lateral raise") 
                || lower.contains("front raise") || lower.contains("arnold") || lower.contains("deltoid")) {
            return "Shoulders";
        }
        if (lower.contains("squat") || lower.contains("leg press") || lower.contains("leg extension") 
                || lower.contains("leg curl") || lower.contains("gambe") || lower.contains("legs") 
                || lower.contains("affondi") || lower.contains("lunge") || lower.contains("polpacci") 
                || lower.contains("calf") || lower.contains("quadricipiti")) {
            return "Legs";
        }
        if (lower.contains("bicipiti") || lower.contains("biceps") || lower.contains("curl") 
                || lower.contains("hammer")) {
            return "Biceps";
        }
        if (lower.contains("tricipiti") || lower.contains("triceps") || lower.contains("french") 
                || lower.contains("pushdown") || lower.contains("push down") || lower.contains("kickback")) {
            return "Triceps";
        }
        if (lower.contains("crunch") || lower.contains("plank") || lower.contains("addome") 
                || lower.contains("abs") || lower.contains("core") || lower.contains("sit up") 
                || lower.contains("sit-up")) {
            return "Core";
        }
        return null;
    }

    /**
     * Get the details of the most recent strength workout from the database.
     *
     * @param limit ignored (loads the single latest session from DB)
     * @return result map ready to be serialized as JSON
     */
    public Map<String, Object> getLastStrengthWorkout(int limit) {
        Optional<StrengthWorkout> latestOpt = workoutRepository.findFirstByOrderByWorkoutDateDescWorkoutTimeDesc();
        if (latestOpt.isEmpty()) {
            return Map.of("status", "error",
                    "detail", "No strength training activity found in the database. Sync Garmin first.");
        }

        StrengthWorkout workout = latestOpt.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", workout.getActivityId());
        result.put("activityName", workout.getActivityName());
        
        String startStr = workout.getWorkoutDate().toString() + " " + workout.getWorkoutTime().toString();
        result.put("startTimeLocal", startStr);
        result.put("duration", formatDuration(workout.getDurationSeconds() != null ? workout.getDurationSeconds().doubleValue() : 0.0));
        result.put("durationSeconds", workout.getDurationSeconds());
        result.put("calories", workout.getCalories());
        result.put("averageHR", workout.getAverageHr());
        result.put("maxHR", workout.getMaxHr());
        result.put("aerobicTrainingEffect", workout.getAerobicTe());
        result.put("anaerobicTrainingEffect", workout.getAnaerobicTe());

        List<Map<String, Object>> setsList = new ArrayList<>();
        Map<String, Double> volumeByGroup = new LinkedHashMap<>();

        List<StrengthWorkoutSet> workoutSets = workout.getSets() != null ? workout.getSets() : List.of();
        for (StrengthWorkoutSet set : workoutSets) {
            Map<String, Object> setEntry = new LinkedHashMap<>();
            setEntry.put("setId", set.getId());
            setEntry.put("setNumber", set.getSetNumber());
            setEntry.put("exercise", set.getExerciseName());
            setEntry.put("originalExercise", set.getOriginalExerciseName());
            String group = resolveMuscleGroup(set);
            setEntry.put("muscleGroup", group);
            setEntry.put("reps", set.getReps());
            double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
            setEntry.put("weightKg", weight);
            setsList.add(setEntry);

            if (weight > 0 && set.getReps() != null && set.getReps() > 0) {
                volumeByGroup.merge(group, set.getReps() * weight, (oldVal, newVal) -> (oldVal != null ? oldVal : 0.0) + (newVal != null ? newVal : 0.0));
            }
        }

        result.put("sets", setsList);

        Map<String, Double> sortedVolume = new LinkedHashMap<>();
        volumeByGroup.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sortedVolume.put(e.getKey(), Math.round(e.getValue() * 10.0) / 10.0));
        result.put("volumeByMuscleGroup", sortedVolume);

        return Map.of("status", "success", "data", result);
    }

    /**
     * Update the exercise name, muscle group, and weight/reps of a specific set by ID.
     *
     * @param setId database primary key of the set
     * @param customName custom name to assign (null/empty to revert to original)
     * @param muscleGroup explicit muscle group (null to auto-resolve)
     * @param weightKg new weight in kg (null to keep current)
     * @param reps new repetition count (null to keep current)
     * @param applyToAllSimilar if true, applies the name and muscle group to all matching sets in the same session
     */
    public void updateSetDetails(Long setId, String customName, String muscleGroup, Double weightKg, Integer reps, Boolean applyToAllSimilar) {
        if (setId == null) {
            throw new IllegalArgumentException("Set ID must not be null");
        }
        StrengthWorkoutSet targetSet = setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Set not found with ID: " + setId));

        String newExerciseName;
        if (customName == null || customName.isBlank()) {
            newExerciseName = targetSet.getOriginalExerciseName();
        } else {
            newExerciseName = customName.trim();
        }

        String targetMuscleGroup = (muscleGroup != null && !muscleGroup.isBlank())
                ? muscleGroup.trim()
                : resolveMuscleGroup(newExerciseName, targetSet.getOriginalExerciseName(), null);

        BigDecimal newWeight = null;
        if (weightKg != null) {
            if (weightKg < 0) {
                throw new IllegalArgumentException("Weight cannot be negative");
            }
            newWeight = BigDecimal.valueOf(Math.round(weightKg * 10.0) / 10.0);
        }

        Integer newReps = (reps != null && reps >= 0) ? reps : null;

        if (Boolean.TRUE.equals(applyToAllSimilar) && targetSet.getWorkout() != null) {
            String matchingOrigName = targetSet.getOriginalExerciseName();
            String matchingCurName = targetSet.getExerciseName();
            List<StrengthWorkoutSet> allSets = targetSet.getWorkout().getSets();
            if (allSets != null) {
                for (StrengthWorkoutSet s : allSets) {
                    boolean matches = (matchingOrigName != null && matchingOrigName.equals(s.getOriginalExerciseName()))
                            || (matchingCurName != null && matchingCurName.equals(s.getExerciseName()));
                    if (matches) {
                        s.setExerciseName(newExerciseName);
                        s.setMuscleGroup(targetMuscleGroup);
                        if (s.getId().equals(targetSet.getId())) {
                            if (newWeight != null) s.setWeightKg(newWeight);
                            if (newReps != null) s.setReps(newReps);
                        }
                        setRepository.save(s);
                    }
                }
            }
        } else {
            targetSet.setExerciseName(newExerciseName);
            targetSet.setMuscleGroup(targetMuscleGroup);
            if (newWeight != null) targetSet.setWeightKg(newWeight);
            if (newReps != null) targetSet.setReps(newReps);
            setRepository.save(targetSet);
        }
    }

    public void updateSetDetails(Long setId, String customName, Double weightKg, Integer reps) {
        updateSetDetails(setId, customName, null, weightKg, reps, false);
    }

    public void updateSetExerciseName(Long setId, String customName) {
        updateSetDetails(setId, customName, null, null, null, false);
    }

    /**
     * Retrieve weekly training volume aggregated by muscle group.
     */
    public Map<String, Object> getWorkoutHistory() {
        List<StrengthWorkout> workouts = workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc();
        Map<String, Map<String, Double>> weeklyData = new TreeMap<>();

        for (StrengthWorkout workout : workouts) {
            if (workout.getWorkoutDate() == null) continue;
            LocalDate monday = workout.getWorkoutDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            String weekKey = monday.toString();

            Map<String, Double> weekMap = weeklyData.computeIfAbsent(weekKey, k -> new HashMap<>());

            List<StrengthWorkoutSet> sets = workout.getSets() != null ? workout.getSets() : List.of();
            for (StrengthWorkoutSet set : sets) {
                double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
                int reps = set.getReps() != null ? set.getReps() : 0;
                
                if (weight > 0 && reps > 0) {
                    String muscleGroup = resolveMuscleGroup(set);
                    weekMap.merge(muscleGroup, reps * weight, (oldVal, newVal) -> (oldVal != null ? oldVal : 0.0) + (newVal != null ? newVal : 0.0));
                }
            }
        }

        Map<String, Map<String, Double>> roundedWeeklyData = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : weeklyData.entrySet()) {
            Map<String, Double> groupMap = new LinkedHashMap<>();
            for (Map.Entry<String, Double> inner : entry.getValue().entrySet()) {
                groupMap.put(inner.getKey(), Math.round(inner.getValue() * 10.0) / 10.0);
            }
            roundedWeeklyData.put(entry.getKey(), groupMap);
        }

        return Map.of("status", "success", "data", roundedWeeklyData);
    }

    /**
     * Retrieve historical performance progression for a given exercise.
     */
    public Map<String, Object> getExerciseProgression(String exerciseName) {
        if (exerciseName == null || exerciseName.isBlank()) {
            return Map.of("status", "error", "detail", "Exercise name parameter is required");
        }

        List<StrengthWorkout> workouts = workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc();
        List<Map<String, Object>> progression = new ArrayList<>();

        List<StrengthWorkout> chronologicalWorkouts = new ArrayList<>(workouts);
        Collections.reverse(chronologicalWorkouts);

        for (StrengthWorkout workout : chronologicalWorkouts) {
            if (workout.getWorkoutDate() == null) continue;
            double maxWeight = 0.0;
            double max1RM = 0.0;
            boolean performed = false;

            List<StrengthWorkoutSet> sets = workout.getSets() != null ? workout.getSets() : List.of();
            for (StrengthWorkoutSet set : sets) {
                if (exerciseName.equalsIgnoreCase(set.getExerciseName())) {
                    performed = true;
                    double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
                    int reps = set.getReps() != null ? set.getReps() : 0;
                    
                    if (weight > maxWeight) {
                        maxWeight = weight;
                    }
                    
                    double est1RM = calculate1RM(weight, reps);
                    if (est1RM > max1RM) {
                        max1RM = est1RM;
                    }
                }
            }

            if (performed) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", workout.getWorkoutDate().toString());
                entry.put("maxWeightKg", Math.round(maxWeight * 10.0) / 10.0);
                entry.put("estimated1RM", Math.round(max1RM * 10.0) / 10.0);
                progression.add(entry);
            }
        }

        return Map.of("status", "success", "data", progression);
    }

    /**
     * Retrieve list of unique exercise names in the database.
     */
    public List<String> getUniqueExercises() {
        return setRepository.findDistinctExerciseNames();
    }

    private double calculate1RM(double weight, int reps) {
        if (reps <= 0) return 0.0;
        if (reps == 1) return weight;
        return weight * (1.0 + (double) reps / 30.0);
    }

    private static String formatDuration(Double seconds) {
        if (seconds == null || seconds <= 0) return "00:00:00";
        int total = seconds.intValue();
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private static String toTitleCase(String input) {
        if (input == null || input.isBlank()) return input;
        String[] words = input.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
