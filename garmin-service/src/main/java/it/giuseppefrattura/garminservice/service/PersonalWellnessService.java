package it.giuseppefrattura.garminservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

@Service
public class PersonalWellnessService {
    private static final Logger log = LoggerFactory.getLogger(PersonalWellnessService.class);
    private final GarminProxyClient client;
    public PersonalWellnessService(GarminProxyClient client) { this.client = client; }

    public record Day(String date, Double steps, Double calories, Double distance, Double stress, Double restingHeartRate,
                      Double minHeartRate, Double maxHeartRate, Double spo2, Double fitnessAge) {}
    public record Result(String start, String end, List<Day> days, Map<String, Integer> failedRequests) {}

    public Result load(LocalDate end, int days) {
        if (!client.personalEnabled()) throw new IllegalStateException("Garmin Personal API key is not configured");
        LocalDate start = end.minusDays(days - 1L);
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        Map<String, JsonNode> steps = new HashMap<>();
        Map<String, JsonNode> summaries = new HashMap<>();
        // Garmin's steps endpoint accepts at most 28 days per request.
        for (LocalDate from = start; !from.isAfter(end); from = from.plusDays(28)) {
            LocalDate to = from.plusDays(27).isAfter(end) ? end : from.plusDays(27);
            index(fetch("passi", "/usersummary-service/stats/steps/daily/" + from + "/" + to, Map.of(), failures), steps);
        }
        index(fetch("riepiloghi", "/usersummary-service/v2/dailysummaries", Map.of("startDate", start.toString(), "endDate", end.toString()), failures), summaries);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<CompletableFuture<Day>> futures = new ArrayList<>();
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                final String d = date.toString();
                futures.add(CompletableFuture.supplyAsync(() -> {
                    JsonNode hr = fetch("frequenza cardiaca", "/wellness-service/wellness/dailyHeartRate", Map.of("date", d), failures);
                    JsonNode oxygen = fetch("SpO₂", "/wellness-service/wellness/daily/spo2acclimation/" + d, Map.of(), failures);
                    JsonNode age = fetch("età fitness", "/fitnessage-service/fitnessage/v2/" + d, Map.of(), failures);
                    JsonNode summary = summaries.get(d);
                    JsonNode step = steps.get(d);
                    return new Day(d, value(step, "totalSteps"), value(summary == null ? null : summary.path("calories"), "burnedTotal"),
                            value(step, "totalDistance"), value(summary == null ? null : summary.path("stress"), "avgLevel"), value(hr, "restingHeartRate"), value(hr, "minHeartRate"),
                            value(hr, "maxHeartRate"), value(oxygen, "averageSpO2"), value(age, "fitnessAge"));
                }, executor));
            }
            return new Result(start.toString(), end.toString(), futures.stream().map(CompletableFuture::join).toList(), new TreeMap<>(failures));
        } finally { executor.shutdown(); }
    }
    private JsonNode fetch(String kind, String path, Map<String, String> params, Map<String, Integer> failures) {
        try { return client.personalGet(path, params); }
        catch (org.springframework.web.client.RestClientException e) {
            log.warn("Personal API request failed for {}: {}", kind, e.getClass().getSimpleName());
            failures.merge(kind, 1, Integer::sum);
            return null;
        }
    }
    private static void index(JsonNode array, Map<String, JsonNode> target) {
        if (array != null && array.isObject()) array = array.get("data");
        if (array != null && array.isArray()) array.forEach(item -> {
            JsonNode date = item.get("calendarDate");
            if (date != null && date.isTextual()) target.put(date.asText(), item);
        });
    }
    private static Double value(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode value = node.get(key);
        return value != null && value.isNumber() && value.doubleValue() >= 0 ? value.doubleValue() : null;
    }
}
