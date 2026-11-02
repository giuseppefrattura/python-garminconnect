package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
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
import it.giuseppefrattura.garminservice.util.OneRepMaxCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
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

    private static final String MUSCLE_CHEST = "Chest";
    private static final String MUSCLE_BACK = "Back";
    private static final String MUSCLE_LEGS = "Legs";
    private static final String MUSCLE_SHOULDERS = "Shoulders";
    private static final String MUSCLE_BICEPS = "Biceps";
    private static final String MUSCLE_TRICEPS = "Triceps";
    private static final String MUSCLE_CORE = "Core";
    private static final String MUSCLE_DEFAULT = "Altro";
    private static final String UNKNOWN_EXERCISE = "Unknown Exercise";

    private final GarminProxyClient proxy;
    private final StrengthWorkoutRepository workoutRepository;
    private final StrengthWorkoutSetRepository setRepository;
    private final PersonalRecordService personalRecordService;

    /**
     * Mapping from Garmin exercise categories to human-readable muscle groups.
     */
    private static final Map<String, String> MUSCLE_GROUP_MAP = Map.ofEntries(
            Map.entry("BENCH_PRESS", MUSCLE_CHEST),
            Map.entry("CHEST_FLY", MUSCLE_CHEST),
            Map.entry("PUSH_UP", MUSCLE_CHEST),
            Map.entry("PULL_UP", MUSCLE_BACK),
            Map.entry("ROW", MUSCLE_BACK),
            Map.entry("LAT_PULLDOWN", MUSCLE_BACK),
            Map.entry("DEADLIFT", "Back/Legs (Posterior Chain)"),
            Map.entry("SQUAT", MUSCLE_LEGS),
            Map.entry("LUNGE", MUSCLE_LEGS),
            Map.entry("LEG_PRESS", MUSCLE_LEGS),
            Map.entry("LEG_CURL", MUSCLE_LEGS),
            Map.entry("LEG_EXTENSION", MUSCLE_LEGS),
            Map.entry("CALF_RAISE", "Calves"),
            Map.entry("SHOULDER_PRESS", MUSCLE_SHOULDERS),
            Map.entry("LATERAL_RAISE", MUSCLE_SHOULDERS),
            Map.entry("FRONT_RAISE", MUSCLE_SHOULDERS),
            Map.entry("BICEP_CURL", MUSCLE_BICEPS),
            Map.entry("TRICEPS_EXTENSION", MUSCLE_TRICEPS),
            Map.entry("CRUNCH", MUSCLE_CORE),
            Map.entry("SIT_UP", MUSCLE_CORE),
            Map.entry("PLANK", MUSCLE_CORE),
            Map.entry("CORE", MUSCLE_CORE),
            Map.entry("OLYMPIC_LIFT", "Full Body (Olympic)"),
            Map.entry("CARRY", "Full Body/Core"),
            Map.entry("FARMERS_WALK", "Full Body/Core")
    );

    private static final List<Map.Entry<String, List<String>>> KEYWORD_MUSCLE_RULES = List.of(
            Map.entry(MUSCLE_CHEST, List.of("panca", "bench", "petto", "chest", "croci", "push up", "push-up", "dip", "spinte")),
            Map.entry(MUSCLE_BACK, List.of("lat machine", "lat pull", "lat_pull", "trazioni", "pull up", "pull-up", "rematore", "row", "pulley", "dorso", "back", "stacco", "deadlift", "shrug")),
            Map.entry(MUSCLE_SHOULDERS, List.of("spalle", "shoulder", "military", "lento", "alzate", "lateral raise", "front raise", "arnold", "deltoid")),
            Map.entry(MUSCLE_LEGS, List.of("squat", "leg press", "leg extension", "leg curl", "gambe", "legs", "affondi", "lunge", "polpacci", "calf", "quadricipiti")),
            Map.entry(MUSCLE_BICEPS, List.of("bicipiti", "biceps", "curl", "hammer")),
            Map.entry(MUSCLE_TRICEPS, List.of("tricipiti", "triceps", "french", "pushdown", "push down", "kickback")),
            Map.entry(MUSCLE_CORE, List.of("crunch", "plank", "addome", "abs", "core", "sit up", "sit-up"))
    );

    public StrengthWorkoutService(
            GarminProxyClient proxy,
            StrengthWorkoutRepository workoutRepository,
            StrengthWorkoutSetRepository setRepository,
            PersonalRecordService personalRecordService) {
        this.proxy = proxy;
        this.workoutRepository = workoutRepository;
        this.setRepository = setRepository;
        this.personalRecordService = personalRecordService;
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
                .filter(StrengthWorkoutService::isStrengthActivity)
                .toList();

        for (ActivityDto act : strengthActivities) {
            if (syncSingleStrengthWorkout(act)) {
                savedCount++;
            }
        }

        if (savedCount > 0 && personalRecordService != null) {
            personalRecordService.recalculateAllRecords();
        }

        log.info("Finished strength workouts synchronization. Saved {} new workouts.", savedCount);
        return savedCount;
    }

    private static boolean isStrengthActivity(ActivityDto activity) {
        String typeKey = activity.activityType() != null ? activity.activityType().typeKey() : null;
        if (typeKey == null) {
            return false;
        }
        String lower = typeKey.toLowerCase();
        return lower.contains("strength") || lower.contains("training");
    }

    private boolean syncSingleStrengthWorkout(ActivityDto act) {
        long activityId = act.activityId();
        if (workoutRepository.existsByActivityId(activityId)) {
            log.debug("Strength workout {} already exists in database, skipping", activityId);
            return false;
        }

        log.info("Syncing new strength workout: {} (ID: {})", act.activityName(), activityId);
        StrengthWorkout workout = buildWorkoutEntity(act);
        attachExerciseSetsFromProxy(workout, activityId);
        workoutRepository.save(workout);
        return true;
    }

    private StrengthWorkout buildWorkoutEntity(ActivityDto act) {
        StrengthWorkout workout = new StrengthWorkout();
        workout.setActivityId(act.activityId());
        workout.setActivityName(act.activityName() != null ? act.activityName() : "Unknown");
        applyStartDateTime(workout, act.startTimeLocal());
        workout.setDurationSeconds(act.duration() != null ? act.duration().intValue() : 0);
        workout.setCalories(act.calories() != null ? act.calories() : 0);
        workout.setAverageHr(act.averageHR());
        workout.setMaxHr(act.maxHR());
        workout.setAerobicTe(act.aerobicTrainingEffect() != null ? BigDecimal.valueOf(act.aerobicTrainingEffect()) : null);
        workout.setAnaerobicTe(act.anaerobicTrainingEffect() != null ? BigDecimal.valueOf(act.anaerobicTrainingEffect()) : null);
        return workout;
    }

    private void applyStartDateTime(StrengthWorkout workout, String startStr) {
        if (startStr != null && startStr.length() >= 19) {
            try {
                workout.setWorkoutDate(LocalDate.parse(startStr.substring(0, 10)));
                workout.setWorkoutTime(LocalTime.parse(startStr.substring(11, 19)));
                return;
            } catch (Exception e) {
                log.warn("Could not parse start time '{}': {}", startStr, e.getMessage());
            }
        }
        workout.setWorkoutDate(LocalDate.now());
        workout.setWorkoutTime(LocalTime.MIDNIGHT);
    }

    private void attachExerciseSetsFromProxy(StrengthWorkout workout, long activityId) {
        try {
            ExerciseSetsResponse setsResponse = proxy.getExerciseSets(activityId);
            if (setsResponse == null || setsResponse.exerciseSets() == null) {
                return;
            }
            int setNum = 1;
            for (ExerciseSetDto exSet : setsResponse.exerciseSets()) {
                StrengthWorkoutSet setEntry = buildWorkoutSetEntity(exSet, setNum);
                if (setEntry != null) {
                    setNum++;
                    workout.addSet(setEntry);
                }
            }
        } catch (Exception ex) {
            log.warn("Could not fetch exercise sets for activity during sync {}: {}", activityId, ex.getMessage());
        }
    }

    private StrengthWorkoutSet buildWorkoutSetEntity(ExerciseSetDto exSet, int setNum) {
        if ("REST".equals(exSet.setType())) {
            return null;
        }
        String exName = extractExerciseDisplayName(exSet);
        int reps = exSet.repetitionCount() != null ? exSet.repetitionCount() : 0;
        double rawWeight = exSet.weight() != null ? exSet.weight() : 0.0;
        double weightKg = rawWeight > 0 ? rawWeight / 1000.0 : 0.0;

        if (reps <= 0 && weightKg <= 0 && UNKNOWN_EXERCISE.equals(exName)) {
            return null;
        }

        StrengthWorkoutSet setEntry = new StrengthWorkoutSet();
        setEntry.setSetNumber(setNum);
        setEntry.setOriginalExerciseName(exName);
        setEntry.setExerciseName(exName);
        setEntry.setMuscleGroup(resolveMuscleGroup(exName, exName, null));
        setEntry.setReps(reps);
        setEntry.setWeightKg(BigDecimal.valueOf(round1(weightKg)));
        return setEntry;
    }

    private String extractExerciseDisplayName(ExerciseSetDto exSet) {
        if (exSet.exercises() == null || exSet.exercises().isEmpty()) {
            return UNKNOWN_EXERCISE;
        }
        ExerciseDto first = exSet.exercises().get(0);
        if (first.name() != null && !first.name().isBlank()) {
            return toTitleCase(first.name().replace("_", " "));
        }
        if (first.category() != null && !first.category().isBlank()) {
            return toTitleCase(first.category().replace("_", " "));
        }
        return UNKNOWN_EXERCISE;
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
        if (set == null) return MUSCLE_DEFAULT;
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

        return MUSCLE_DEFAULT;
    }

    private String inferMuscleGroupFromName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase();
        for (Map.Entry<String, List<String>> rule : KEYWORD_MUSCLE_RULES) {
            if (containsAnyKeyword(lower, rule.getValue())) {
                return rule.getKey();
            }
        }
        return null;
    }

    private static boolean containsAnyKeyword(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the details of the most recent strength workout from the database.
     *
     * @return typed response with the latest session or an error detail if none exists
     */
    public ApiResponse<LastStrengthWorkoutDto> getLastStrengthWorkout() {
        Optional<StrengthWorkout> latestOpt = workoutRepository.findFirstByOrderByWorkoutDateDescWorkoutTimeDesc();
        if (latestOpt.isEmpty()) {
            return ApiResponse.error("No strength training activity found in the database. Sync Garmin first.");
        }

        StrengthWorkout workout = latestOpt.get();
        Map<Long, List<String>> prFlags = fetchPrFlagsForWorkout(workout);

        List<LastStrengthWorkoutDto.WorkoutSetDto> setsList = new ArrayList<>();
        Map<String, Double> volumeByGroup = new LinkedHashMap<>();

        List<StrengthWorkoutSet> workoutSets = workout.getSets() != null ? workout.getSets() : List.of();
        for (StrengthWorkoutSet set : workoutSets) {
            setsList.add(buildWorkoutSetDto(set, prFlags, volumeByGroup));
        }

        Map<String, Double> sortedVolume = sortAndRoundVolume(volumeByGroup);
        double durationSec = workout.getDurationSeconds() != null ? workout.getDurationSeconds().doubleValue() : 0.0;

        LastStrengthWorkoutDto data = new LastStrengthWorkoutDto(
                workout.getActivityId(),
                workout.getActivityName(),
                workout.getWorkoutDate().toString() + " " + workout.getWorkoutTime().toString(),
                formatDuration(durationSec),
                workout.getDurationSeconds(),
                workout.getCalories(),
                workout.getAverageHr(),
                workout.getMaxHr(),
                workout.getAerobicTe(),
                workout.getAnaerobicTe(),
                setsList,
                sortedVolume);

        return ApiResponse.success(data);
    }

    private Map<Long, List<String>> fetchPrFlagsForWorkout(StrengthWorkout workout) {
        if (personalRecordService == null || workout.getActivityId() == null) {
            return Map.of();
        }
        return personalRecordService.getPrFlagsForActivity(workout.getActivityId());
    }

    private LastStrengthWorkoutDto.WorkoutSetDto buildWorkoutSetDto(
            StrengthWorkoutSet set,
            Map<Long, List<String>> prFlags,
            Map<String, Double> volumeByGroup) {
        String group = resolveMuscleGroup(set);
        double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
        List<String> prTypes = set.getId() != null ? prFlags.getOrDefault(set.getId(), List.of()) : List.of();

        int reps = set.getReps() != null ? set.getReps() : 0;
        if (weight > 0 && reps > 0) {
            volumeByGroup.merge(group, reps * weight, Double::sum);
        }

        return new LastStrengthWorkoutDto.WorkoutSetDto(
                set.getId(),
                set.getSetNumber(),
                set.getExerciseName(),
                set.getOriginalExerciseName(),
                group,
                set.getReps(),
                weight,
                !prTypes.isEmpty(),
                prTypes);
    }

    private Map<String, Double> sortAndRoundVolume(Map<String, Double> volumeByGroup) {
        Map<String, Double> sortedVolume = new LinkedHashMap<>();
        volumeByGroup.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sortedVolume.put(e.getKey(), round1(e.getValue())));
        return sortedVolume;
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

        String newExerciseName = (customName == null || customName.isBlank())
                ? targetSet.getOriginalExerciseName()
                : customName.trim();

        String targetMuscleGroup = (muscleGroup != null && !muscleGroup.isBlank())
                ? muscleGroup.trim()
                : resolveMuscleGroup(newExerciseName, targetSet.getOriginalExerciseName(), null);

        BigDecimal newWeight = weightKg != null ? BigDecimal.valueOf(round1(Math.max(0.0, weightKg))) : null;
        Integer newReps = reps != null ? Math.max(0, reps) : null;

        if (Boolean.TRUE.equals(applyToAllSimilar) && targetSet.getWorkout() != null) {
            applyUpdatesToSimilarSets(targetSet, newExerciseName, targetMuscleGroup, newWeight, newReps);
        } else {
            applyUpdatesToSingleSet(targetSet, newExerciseName, targetMuscleGroup, newWeight, newReps);
        }

        if (personalRecordService != null) {
            personalRecordService.recalculateAllRecords();
        }
    }

    private void applyUpdatesToSimilarSets(StrengthWorkoutSet targetSet,
                                           String newExerciseName,
                                           String targetMuscleGroup,
                                           BigDecimal newWeight,
                                           Integer newReps) {
        List<StrengthWorkoutSet> allSets = targetSet.getWorkout().getSets();
        if (allSets == null) {
            return;
        }
        String matchingOrigName = targetSet.getOriginalExerciseName();
        String matchingCurName = targetSet.getExerciseName();

        for (StrengthWorkoutSet s : allSets) {
            if (isMatchingSetName(s, matchingOrigName, matchingCurName)) {
                boolean isTarget = s.getId() != null && s.getId().equals(targetSet.getId());
                applyUpdatesToSingleSet(s, newExerciseName, targetMuscleGroup,
                        isTarget ? newWeight : null,
                        isTarget ? newReps : null);
            }
        }
    }

    private boolean isMatchingSetName(StrengthWorkoutSet candidate, String origName, String curName) {
        return (origName != null && origName.equals(candidate.getOriginalExerciseName()))
                || (curName != null && curName.equals(candidate.getExerciseName()));
    }

    private void applyUpdatesToSingleSet(StrengthWorkoutSet set,
                                         String newExerciseName,
                                         String targetMuscleGroup,
                                         BigDecimal newWeight,
                                         Integer newReps) {
        set.setExerciseName(newExerciseName);
        set.setMuscleGroup(targetMuscleGroup);
        if (newWeight != null) {
            set.setWeightKg(newWeight);
        }
        if (newReps != null) {
            set.setReps(newReps);
        }
        setRepository.save(set);
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
    public ApiResponse<Map<String, Map<String, Double>>> getWorkoutHistory() {
        List<StrengthWorkout> workouts = workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc();
        Map<String, Map<String, Double>> weeklyData = new TreeMap<>();

        for (StrengthWorkout workout : workouts) {
            accumulateWorkoutWeeklyVolume(workout, weeklyData);
        }

        return ApiResponse.success(roundWeeklyVolumes(weeklyData));
    }

    private void accumulateWorkoutWeeklyVolume(StrengthWorkout workout, Map<String, Map<String, Double>> weeklyData) {
        if (workout.getWorkoutDate() == null) {
            return;
        }
        LocalDate monday = workout.getWorkoutDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Double> weekMap = weeklyData.computeIfAbsent(monday.toString(), k -> new HashMap<>());

        List<StrengthWorkoutSet> sets = workout.getSets() != null ? workout.getSets() : List.of();
        for (StrengthWorkoutSet set : sets) {
            double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
            int reps = set.getReps() != null ? set.getReps() : 0;
            if (weight > 0 && reps > 0) {
                weekMap.merge(resolveMuscleGroup(set), reps * weight, Double::sum);
            }
        }
    }

    private Map<String, Map<String, Double>> roundWeeklyVolumes(Map<String, Map<String, Double>> weeklyData) {
        Map<String, Map<String, Double>> roundedWeeklyData = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : weeklyData.entrySet()) {
            Map<String, Double> groupMap = new LinkedHashMap<>();
            for (Map.Entry<String, Double> inner : entry.getValue().entrySet()) {
                groupMap.put(inner.getKey(), round1(inner.getValue()));
            }
            roundedWeeklyData.put(entry.getKey(), groupMap);
        }
        return roundedWeeklyData;
    }

    /**
     * Retrieve historical performance progression for a given exercise.
     */
    public ApiResponse<List<ProgressionPointDto>> getExerciseProgression(String exerciseName) {
        if (exerciseName == null || exerciseName.isBlank()) {
            return ApiResponse.error("Exercise name parameter is required");
        }

        List<StrengthWorkout> chronologicalWorkouts = new ArrayList<>(
                workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc());
        Collections.reverse(chronologicalWorkouts);

        List<ProgressionPointDto> progression = new ArrayList<>();
        for (StrengthWorkout workout : chronologicalWorkouts) {
            ProgressionPointDto point = evaluateWorkoutProgressionPoint(workout, exerciseName);
            if (point != null) {
                progression.add(point);
            }
        }

        return ApiResponse.success(progression);
    }

    private ProgressionPointDto evaluateWorkoutProgressionPoint(StrengthWorkout workout, String exerciseName) {
        if (workout.getWorkoutDate() == null || workout.getSets() == null) {
            return null;
        }
        double maxWeight = 0.0;
        double max1RM = 0.0;
        boolean performed = false;

        for (StrengthWorkoutSet set : workout.getSets()) {
            if (exerciseName.equalsIgnoreCase(set.getExerciseName())) {
                performed = true;
                double weight = set.getWeightKg() != null ? set.getWeightKg().doubleValue() : 0.0;
                int reps = set.getReps() != null ? set.getReps() : 0;
                maxWeight = Math.max(maxWeight, weight);
                max1RM = Math.max(max1RM, OneRepMaxCalculator.estimate1Rm(weight, reps));
            }
        }

        if (!performed) {
            return null;
        }
        return new ProgressionPointDto(
                workout.getWorkoutDate().toString(),
                round1(maxWeight),
                round1(max1RM));
    }

    /**
     * Retrieve list of unique exercise names in the database.
     */
    public List<String> getUniqueExercises() {
        return setRepository.findDistinctExerciseNames();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
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
