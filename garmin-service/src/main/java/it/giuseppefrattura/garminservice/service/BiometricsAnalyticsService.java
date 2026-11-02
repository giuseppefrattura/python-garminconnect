package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BiometricsAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(BiometricsAnalyticsService.class);

    private final RestTemplate restTemplate;
    private final String renphoServiceUrl;
    private final PersonalRecordService personalRecordService;
    private final StrengthWorkoutRepository workoutRepository;

    private record BodyCompositionSnapshot(Double weightKg, Double bodyFatPct, Double ffmKg) {}

    public BiometricsAnalyticsService(
            @Value("${garmin.renpho.url:http://renpho-service:8082}") String renphoServiceUrl,
            PersonalRecordService personalRecordService,
            StrengthWorkoutRepository workoutRepository) {
        this.renphoServiceUrl = renphoServiceUrl;
        this.personalRecordService = personalRecordService;
        this.workoutRepository = workoutRepository;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    BiometricsAnalyticsService(
            String renphoServiceUrl,
            PersonalRecordService personalRecordService,
            StrengthWorkoutRepository workoutRepository,
            RestTemplate restTemplate) {
        this.renphoServiceUrl = renphoServiceUrl;
        this.personalRecordService = personalRecordService;
        this.workoutRepository = workoutRepository;
        this.restTemplate = restTemplate;
    }

    /**
     * Fetch raw Renpho measurements from the Renpho service.
     */
    public List<Map<String, Object>> getRenphoMeasurements() {
        try {
            String url = renphoServiceUrl + "/api/renpho/measurements";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url,
                    Objects.requireNonNull(HttpMethod.GET),
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Could not fetch Renpho measurements from {}: {}", renphoServiceUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Calculate Relative Strength indices (1RM / Body Weight, 1RM / FFM) for key exercises.
     */
    public Map<String, Object> getRelativeStrengthOverview() {
        BodyCompositionSnapshot snapshot = extractLatestBodyComposition(getRenphoMeasurements());
        List<PersonalRecordService.TrophyCardDTO> trophyCards = personalRecordService.getTrophyRoomCards();
        List<Map<String, Object>> relativeCards = new ArrayList<>();

        for (PersonalRecordService.TrophyCardDTO card : trophyCards) {
            Map<String, Object> item = toRelativeStrengthItem(card, snapshot);
            if (item != null) {
                relativeCards.add(item);
            }
        }

        return Map.of(
                "status", "success",
                "currentWeightKg", snapshot.weightKg() != null ? snapshot.weightKg() : 0.0,
                "currentBodyFatPct", snapshot.bodyFatPct() != null ? snapshot.bodyFatPct() : 0.0,
                "currentFfmKg", snapshot.ffmKg() != null ? Math.round(snapshot.ffmKg() * 10.0) / 10.0 : 0.0,
                "exercises", relativeCards
        );
    }

    private BodyCompositionSnapshot extractLatestBodyComposition(List<Map<String, Object>> measurements) {
        if (measurements.isEmpty()) {
            return new BodyCompositionSnapshot(null, null, null);
        }
        Map<String, Object> latest = measurements.get(0);
        Double weight = latest.get("weight") instanceof Number w ? w.doubleValue() : null;
        Double bodyFat = latest.get("bodyfat") instanceof Number bf ? bf.doubleValue() : null;
        Double ffm = (weight != null && bodyFat != null) ? weight * (1.0 - (bodyFat / 100.0)) : null;
        return new BodyCompositionSnapshot(weight, bodyFat, ffm);
    }

    private Map<String, Object> toRelativeStrengthItem(PersonalRecordService.TrophyCardDTO card, BodyCompositionSnapshot snapshot) {
        Double est1Rm = card.getEstimated1RmKg();
        if (est1Rm == null || est1Rm <= 0) {
            return null;
        }
        Map<String, Object> item = new HashMap<>();
        item.put("exerciseName", card.getExerciseName());
        item.put("muscleGroup", card.getMuscleGroup());
        item.put("estimated1RmKg", est1Rm);
        item.put("maxWeightKg", card.getMaxWeightKg());
        item.put("maxWeightReps", card.getMaxWeightReps());
        item.put("relativeToBw", computeRatio(est1Rm, snapshot.weightKg()));
        item.put("relativeToFfm", computeRatio(est1Rm, snapshot.ffmKg()));
        return item;
    }

    private static Double computeRatio(double numerator, Double denominator) {
        if (denominator == null || denominator <= 0) {
            return null;
        }
        return Math.round((numerator / denominator) * 100.0) / 100.0;
    }

    /**
     * Generate historical correlation data between Body Composition (Weight & BF%) and Training Volume.
     */
    public Map<String, Object> getBodyRecompositionTrend() {
        List<Map<String, Object>> measurements = getRenphoMeasurements();
        List<StrengthWorkout> workouts = workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc();

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Map workout volume by date
        Map<String, Double> volumeByDate = new HashMap<>();
        for (StrengthWorkout w : workouts) {
            if (w.getWorkoutDate() != null) {
                String d = w.getWorkoutDate().format(dtf);
                double vol = calculateWorkoutVolume(w);
                volumeByDate.put(d, volumeByDate.getOrDefault(d, 0.0) + vol);
            }
        }

        List<Map<String, Object>> trendPoints = new ArrayList<>();

        // Process measurements (limit to last 60 for clean chart view)
        int limit = Math.min(measurements.size(), 60);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> m = measurements.get(i);
            String rawDate = m.get("created_at") != null ? m.get("created_at").toString() : null;
            if (rawDate == null || rawDate.length() < 10) continue;

            String dateStr = rawDate.substring(0, 10);
            Double weight = m.get("weight") instanceof Number num ? num.doubleValue() : null;
            Double bodyFat = m.get("bodyfat") instanceof Number num ? num.doubleValue() : null;
            Double muscle = m.get("muscle") instanceof Number num ? num.doubleValue() : null;

            Map<String, Object> point = new HashMap<>();
            point.put("date", dateStr);
            point.put("weightKg", weight);
            point.put("bodyFatPct", bodyFat);
            point.put("musclePct", muscle);
            point.put("workoutVolumeKg", volumeByDate.getOrDefault(dateStr, 0.0));

            trendPoints.add(point);
        }

        // Chronological order for time series chart
        Collections.reverse(trendPoints);

        return Map.of(
                "status", "success",
                "totalPoints", trendPoints.size(),
                "data", trendPoints
        );
    }

    private double calculateWorkoutVolume(StrengthWorkout workout) {
        if (workout.getSets() == null) return 0.0;
        return workout.getSets().stream()
                .filter(s -> s.getWeightKg() != null && s.getReps() != null)
                .mapToDouble(s -> s.getWeightKg().doubleValue() * s.getReps())
                .sum();
    }
}
