package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseSetDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse.ExerciseDto;
import it.giuseppefrattura.garminservice.model.ExerciseNameMapping;
import it.giuseppefrattura.garminservice.repository.ExerciseNameMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Business logic for strength/weight training analysis.
 * <p>
 * Fetches recent activities from the garmin-proxy, identifies the latest
 * strength workout, and computes per-set details and volume by muscle group.
 */
@Service
public class StrengthWorkoutService {

    private static final Logger log = LoggerFactory.getLogger(StrengthWorkoutService.class);

    private final GarminProxyClient proxy;
    private final ExerciseNameMappingRepository mappingRepository;

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

    public StrengthWorkoutService(GarminProxyClient proxy, ExerciseNameMappingRepository mappingRepository) {
        this.proxy = proxy;
        this.mappingRepository = mappingRepository;
    }

    /**
     * Find the most recent strength training activity and return a full
     * breakdown including sets, reps, weights, and volume by muscle group.
     *
     * @param limit maximum number of recent activities to search through
     * @return result map ready to be serialised as JSON
     */
    public Map<String, Object> getLastStrengthWorkout(int limit) {
        List<ActivityDto> activities = proxy.getActivities(0, limit);

        // Find the first strength/training activity
        ActivityDto strength = activities.stream()
                .filter(a -> {
                    String typeKey = a.activityType() != null ? a.activityType().typeKey() : "";
                    return typeKey != null
                            && (typeKey.toLowerCase().contains("strength")
                            || typeKey.toLowerCase().contains("training"));
                })
                .findFirst()
                .orElse(null);

        if (strength == null) {
            return Map.of("status", "error",
                    "detail", "No strength training activity found in the last " + limit + " activities.");
        }

        long activityId = strength.activityId();

        // General stats
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", activityId);
        result.put("activityName", strength.activityName() != null ? strength.activityName() : "Unknown");
        result.put("startTimeLocal", strength.startTimeLocal() != null ? strength.startTimeLocal() : "Unknown");
        result.put("duration", formatDuration(strength.duration()));
        result.put("durationSeconds", strength.duration() != null ? strength.duration() : 0);
        result.put("calories", strength.calories() != null ? strength.calories() : 0);
        result.put("averageHR", strength.averageHR());
        result.put("maxHR", strength.maxHR());
        result.put("aerobicTrainingEffect", strength.aerobicTrainingEffect());
        result.put("anaerobicTrainingEffect", strength.anaerobicTrainingEffect());

        // Exercise sets
        List<Map<String, Object>> sets = new ArrayList<>();
        Map<String, Double> volumeByGroup = new LinkedHashMap<>();

        // Fetch custom mappings
        Map<String, String> customNamesMap = new HashMap<>();
        try {
            List<ExerciseNameMapping> mappings = mappingRepository.findAll();
            for (ExerciseNameMapping m : mappings) {
                customNamesMap.put(m.getOriginalName(), m.getCustomName());
            }
        } catch (Exception ex) {
            log.error("Could not load exercise name mappings from database", ex);
        }

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

                    String originalName = exName;
                    String customName = customNamesMap.get(originalName);
                    if (customName != null && !customName.isBlank()) {
                        exName = customName;
                    }

                    int reps = exSet.repetitionCount() != null ? exSet.repetitionCount() : 0;
                    double rawWeight = exSet.weight() != null ? exSet.weight() : 0.0;
                    double weightKg = rawWeight > 0 ? rawWeight / 1000.0 : 0.0;

                    if (reps > 0 || weightKg > 0 || !"Unknown Exercise".equals(exName)) {
                        Map<String, Object> setEntry = new LinkedHashMap<>();
                        setEntry.put("setNumber", setNum++);
                        setEntry.put("exercise", exName);
                        setEntry.put("originalExercise", originalName);
                        setEntry.put("reps", reps);
                        setEntry.put("weightKg", Math.round(weightKg * 10.0) / 10.0);
                        sets.add(setEntry);

                        // Volume calculation
                        if (weightKg > 0 && reps > 0) {
                            String muscleGroup = category != null
                                    ? MUSCLE_GROUP_MAP.getOrDefault(category,
                                    toTitleCase(category.replace("_", " ")))
                                    : originalName;
                            volumeByGroup.merge(muscleGroup, reps * weightKg, Double::sum);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not fetch exercise sets for activity {}: {}", activityId, ex.getMessage());
        }

        result.put("sets", sets);

        // Sort volume descending
        Map<String, Double> sortedVolume = new LinkedHashMap<>();
        volumeByGroup.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sortedVolume.put(e.getKey(), Math.round(e.getValue() * 10.0) / 10.0));
        result.put("volumeByMuscleGroup", sortedVolume);

        return Map.of("status", "success", "data", result);
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

    /**
     * Persist or remove a custom exercise name mapping.
     */
    public void saveExerciseNameMapping(String originalName, String customName) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("Original exercise name cannot be empty");
        }
        if (customName == null || customName.isBlank()) {
            if (mappingRepository.existsById(originalName)) {
                mappingRepository.deleteById(originalName);
            }
        } else {
            mappingRepository.save(new ExerciseNameMapping(originalName, customName));
        }
    }
}
