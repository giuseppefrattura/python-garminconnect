package it.giuseppefrattura.garminservice.client;

import it.giuseppefrattura.garminservice.dto.ActivityDto;
import it.giuseppefrattura.garminservice.dto.ExerciseSetsResponse;
import it.giuseppefrattura.garminservice.dto.HrZoneDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.NonNull;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * HTTP client that talks to the garmin-proxy FastAPI service.
 * <p>
 * All methods are retried up to 3 times with exponential backoff (1s → 2s → 4s)
 * when the proxy is temporarily unreachable.
 */
@Component
public class GarminProxyClient {

    private static final Logger log = LoggerFactory.getLogger(GarminProxyClient.class);
    private final RestClient restClient;

    public GarminProxyClient(
            @Value("${garmin.proxy.base-url}") @NonNull String baseUrl,
            @Value("${garmin.proxy.api-key:}") String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(Objects.requireNonNull(baseUrl, "baseUrl must not be null"));
        
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
            log.info("GarminProxyClient configured with API Key authentication.");
        } else {
            log.warn("GarminProxyClient configured without API Key authentication (empty or blank).");
        }
        
        this.restClient = builder.build();
        log.info("GarminProxyClient configured with base URL: {}", baseUrl);
    }

    /**
     * Fetch recent activities.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<ActivityDto> getActivities(int start, int limit) {
        log.debug("Fetching activities (start={}, limit={})", start, limit);
        List<ActivityDto> result = restClient.get()
                .uri("/api/activities?start={start}&limit={limit}", start, limit)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Collections.emptyList();
    }

    /**
     * Fetch activities within a date range, optionally filtered by type.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<ActivityDto> getActivitiesByDate(String startDate, String endDate, String activityType) {
        log.debug("Fetching activities by date ({} to {}, type={})", startDate, endDate, activityType);
        List<ActivityDto> result = restClient.get()
                .uri("/api/activities/by-date?start={start}&end={end}&activity_type={type}",
                        startDate, endDate, activityType != null ? activityType : "")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Collections.emptyList();
    }

    /**
     * Fetch exercise sets for a specific activity.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public ExerciseSetsResponse getExerciseSets(long activityId) {
        log.debug("Fetching exercise sets for activity {}", activityId);
        return restClient.get()
                .uri("/api/activities/{id}/exercise-sets", activityId)
                .retrieve()
                .body(ExerciseSetsResponse.class);
    }

    /**
     * Fetch HR zone breakdown for a specific activity.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public List<HrZoneDto> getHrZones(long activityId) {
        log.debug("Fetching HR zones for activity {}", activityId);
        List<HrZoneDto> result = restClient.get()
                .uri("/api/activities/{id}/hr-zones", activityId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Collections.emptyList();
    }

    /**
     * Fetch aggregated daily health summary (sleep, HRV, body battery, stress) for a given date.
     */
    @Retryable(
            retryFor = {ResourceAccessException.class, RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    public java.util.Map<String, Object> getDailyHealthSummary(String date) {
        log.debug("Fetching daily health summary for date {}", date);
        java.util.Map<String, Object> result = restClient.get()
                .uri("/api/health/daily-summary?date={date}", date)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : Collections.emptyMap();
    }
}
