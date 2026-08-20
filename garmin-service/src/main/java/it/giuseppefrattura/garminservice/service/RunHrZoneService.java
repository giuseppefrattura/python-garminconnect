package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.HrZoneDto;
import it.giuseppefrattura.garminservice.model.RunningHrZone;
import it.giuseppefrattura.garminservice.repository.RunningHrZoneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for running HR zone analysis and persistence.
 * <p>
 * Fetches recent running activities from the proxy, aggregates HR zone
 * minutes, and optionally persists them to PostgreSQL.
 */
@Service
public class RunHrZoneService {

    private static final Logger log = LoggerFactory.getLogger(RunHrZoneService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GarminProxyClient proxy;
    private final RunningHrZoneRepository repository;
    private final StrengthWorkoutService strengthWorkoutService;

    public RunHrZoneService(
            GarminProxyClient proxy,
            RunningHrZoneRepository repository,
            StrengthWorkoutService strengthWorkoutService) {
        this.proxy = proxy;
        this.repository = repository;
        this.strengthWorkoutService = strengthWorkoutService;
    }

    // ---- internal data carrier ----
    private record RunEntry(
            long activityId,
            String activityName,
            String startTimeLocal,
            LocalDate runDate,
            LocalTime runTime,
            Map<Integer, Double> zonesMins,
            String error
    ) {}

    /**
     * Fetch running activities from the last {@code days} days and compute
     * HR zone minutes for each.
     */
    private List<RunEntry> fetchRecentRuns(int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);

        List<ActivityDto> activities = proxy.getActivitiesByDate(
                start.toString(), today.toString(), "running");

        if (activities == null || activities.isEmpty()) {
            return Collections.emptyList();
        }

        List<RunEntry> entries = new ArrayList<>();
        for (ActivityDto act : activities) {
            Map<Integer, Double> zones = new LinkedHashMap<>();
            for (int z = 1; z <= 5; z++) zones.put(z, 0.0);

            LocalDate runDate = null;
            LocalTime runTime = null;
            if (act.startTimeLocal() != null && !act.startTimeLocal().isBlank()) {
                try {
                    LocalDateTime dt = LocalDateTime.parse(act.startTimeLocal(), DT_FMT);
                    runDate = dt.toLocalDate();
                    runTime = dt.toLocalTime();
                } catch (DateTimeParseException ignored) {}
            }

            String error = null;
            try {
                List<HrZoneDto> hrZones = proxy.getHrZones(act.activityId());
                if (hrZones != null) {
                    for (HrZoneDto hz : hrZones) {
                        if (hz.zoneNumber() != null && zones.containsKey(hz.zoneNumber())) {
                            double secs = hz.secsInZone() != null ? hz.secsInZone() : 0.0;
                            zones.put(hz.zoneNumber(), secs / 60.0);
                        }
                    }
                }
            } catch (Exception ex) {
                error = ex.getMessage();
                log.warn("HR zones fetch error for activity {}: {}", act.activityId(), ex.getMessage());
            }

            entries.add(new RunEntry(
                    act.activityId(),
                    act.activityName() != null ? act.activityName() : "Unknown",
                    act.startTimeLocal() != null ? act.startTimeLocal() : "",
                    runDate, runTime, zones, error));
        }
        return entries;
    }

    /**
     * Return aggregated HR zone minutes for the last {@code days} days.
     */
    public Map<String, Object> getRunHrZones(int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);
        Map<String, String> period = Map.of("from", start.toString(), "to", today.toString());

        List<RunEntry> entries = fetchRecentRuns(days);
        if (entries.isEmpty()) {
            return Map.of("status", "success", "data",
                    Map.of("period", period, "activitiesCount", 0,
                            "zones", Map.of(), "activities", Collections.emptyList()));
        }

        Map<Integer, Double> aggregated = new LinkedHashMap<>();
        for (int z = 1; z <= 5; z++) aggregated.put(z, 0.0);

        List<Map<String, Object>> actList = new ArrayList<>();
        for (RunEntry e : entries) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("activityId", e.activityId());
            out.put("activityName", e.activityName());
            out.put("startTimeLocal", e.startTimeLocal());

            if (e.error() != null) {
                out.put("error", e.error());
            } else {
                Map<String, Double> zonesOut = new LinkedHashMap<>();
                e.zonesMins().forEach((z, mins) -> {
                    if (mins != null && mins > 0) zonesOut.put("zone_" + z, round1(mins));
                    if (mins != null) aggregated.merge(z, mins, (oldVal, newVal) -> (oldVal != null ? oldVal : 0.0) + (newVal != null ? newVal : 0.0));
                });
                out.put("zones", zonesOut);
            }
            actList.add(out);
        }

        Map<String, Double> zonesMinutes = new LinkedHashMap<>();
        aggregated.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> (e.getValue() != null && e.getValue() > 0) || e.getKey() == 1)
                .forEach(e -> zonesMinutes.put("zone_" + e.getKey(), round1(e.getValue() != null ? e.getValue() : 0.0)));

        return Map.of("status", "success", "data",
                Map.of("period", period,
                        "activitiesCount", entries.size(),
                        "zones", zonesMinutes,
                        "activities", actList));
    }

    /**
     * Fetch HR zones, persist to PostgreSQL (upsert), and return saved records.
     */
    @Transactional
    public Map<String, Object> persistRunHrZones(int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);
        Map<String, String> period = Map.of("from", start.toString(), "to", today.toString());

        // Sync strength workouts as part of the unified sync flow
        try {
            strengthWorkoutService.syncStrengthWorkouts(Math.max(20, days * 3));
        } catch (Exception ex) {
            log.warn("Strength workouts sync failed during unified sync flow: {}", ex.getMessage());
        }

        List<RunEntry> entries = fetchRecentRuns(days);
        if (entries.isEmpty()) {
            return Map.of("status", "success", "data",
                    Map.of("period", period, "activitiesCount", 0,
                            "savedCount", 0, "saved", Collections.emptyList()));
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (RunEntry e : entries) {
            if (e.runDate() == null || e.runTime() == null) continue;

            try {
                // Upsert: find existing or create new
                RunningHrZone entity = repository.findByActivityId(e.activityId())
                        .orElseGet(RunningHrZone::new);

                entity.setActivityId(e.activityId());
                entity.setActivityName(e.activityName());
                entity.setRunDate(e.runDate());
                entity.setRunTime(e.runTime());
                entity.setZone1Mins(toBigDecimal(e.zonesMins().getOrDefault(1, 0.0)));
                entity.setZone2Mins(toBigDecimal(e.zonesMins().getOrDefault(2, 0.0)));
                entity.setZone3Mins(toBigDecimal(e.zonesMins().getOrDefault(3, 0.0)));
                entity.setZone4Mins(toBigDecimal(e.zonesMins().getOrDefault(4, 0.0)));
                entity.setZone5Mins(toBigDecimal(e.zonesMins().getOrDefault(5, 0.0)));

                repository.save(entity);

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("activityId", e.activityId());
                record.put("activityName", e.activityName());
                record.put("runDate", e.runDate().toString());
                record.put("runTime", e.runTime().toString());
                record.put("zone1Mins", round2(e.zonesMins().getOrDefault(1, 0.0)));
                record.put("zone2Mins", round2(e.zonesMins().getOrDefault(2, 0.0)));
                record.put("zone3Mins", round2(e.zonesMins().getOrDefault(3, 0.0)));
                record.put("zone4Mins", round2(e.zonesMins().getOrDefault(4, 0.0)));
                record.put("zone5Mins", round2(e.zonesMins().getOrDefault(5, 0.0)));
                saved.add(record);
            } catch (Exception ex) {
                log.warn("DB upsert error for activity {}: {}", e.activityId(), ex.getMessage());
            }
        }

        return Map.of("status", "success", "data",
                Map.of("period", period,
                        "activitiesCount", entries.size(),
                        "savedCount", saved.size(),
                        "saved", saved));
    }

    /**
     * Fetch all persisted running HR zones from the PostgreSQL database.
     */
    public List<RunningHrZone> getRunHrZonesFromDb() {
        log.debug("Fetching all persisted running HR zones from database sorted chronologically");
        return repository.findAllByOrderByRunDateDescRunTimeDesc();
    }

    private static BigDecimal toBigDecimal(Double value) {
        return BigDecimal.valueOf(value != null ? value : 0.0).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
