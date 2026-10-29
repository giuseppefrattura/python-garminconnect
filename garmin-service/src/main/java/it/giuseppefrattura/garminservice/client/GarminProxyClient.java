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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

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
    private final RestClient personalClient;
    private final String personalKey;
    private String personalToken;
    private Instant tokenExpiry = Instant.EPOCH;

    public GarminProxyClient(
            @Value("${garmin.proxy.base-url}") @NonNull String baseUrl,
            @Value("${garmin.proxy.api-key:}") String apiKey,
            @Value("${garmin.personal.base-url:https://personal.connectapi.garmin.com}") String personalUrl,
            @Value("${garmin.personal.api-key:}") String personalKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.personalClient = RestClient.builder().baseUrl(personalUrl).requestFactory(factory).build();
        this.personalKey = personalKey;
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
        if (personalEnabled()) {
            List<ActivityDto> result = personalClient.get()
                    .uri("/activitylist-service/activities/search/activities?start={start}&limit={limit}", start, limit)
                    .headers(h -> h.setBearerAuth(personalToken()))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return result != null ? result : Collections.emptyList();
        }
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
        if (personalEnabled()) {
            List<ActivityDto> all = new ArrayList<>();
            int offset = 0;
            int pageSize = 100;
            while (true) {
                final int pageStart = offset;
                List<ActivityDto> page = personalClient.get().uri(builder -> {
                    var uri = builder.path("/activitylist-service/activities/search/activities")
                            .queryParam("start", pageStart).queryParam("limit", pageSize)
                            .queryParam("startDate", startDate).queryParam("endDate", endDate);
                    if (activityType != null && !activityType.isBlank()) uri.queryParam("activityType", activityType);
                    return uri.build();
                }).headers(h -> h.setBearerAuth(personalToken()))
                        .retrieve().body(new ParameterizedTypeReference<>() {});
                if (page == null || page.isEmpty()) break;
                all.addAll(page);
                if (page.size() < pageSize) break;
                offset += page.size();
            }
            return all;
        }
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
    public boolean personalEnabled() {
        return personalKey != null && !personalKey.isBlank();
    }

    private synchronized String personalToken() {
        if (personalToken == null || Instant.now().isAfter(tokenExpiry)) {
            String token = personalClient.post()
                    .uri("/personalapitoken-service/personal-api/personal-api-key/token")
                    .header("Authorization", "ApiKey " + personalKey)
                    .retrieve().body(String.class);
            if (token == null || token.isBlank()) throw new IllegalStateException("Garmin returned an empty access token");
            personalToken = token.trim();
            try {
                JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
                tokenExpiry = Instant.ofEpochSecond(claims.path("exp").asLong()).minusSeconds(3600);
                if (!tokenExpiry.isAfter(Instant.now())) throw new IllegalStateException("Garmin token is expired or expires soon");
            } catch (java.io.IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
                personalToken = null;
                throw new IllegalStateException("Garmin returned an invalid access token", e);
            }
        }
        return personalToken;
    }

    public JsonNode personalGet(String path, java.util.Map<String, String> params) {
        if (!personalEnabled()) throw new IllegalStateException("Garmin Personal API key is not configured");
        return personalClient.get().uri(builder -> {
            var uri = builder.path(path);
            params.forEach(uri::queryParam);
            return uri.build();
        }).headers(h -> h.setBearerAuth(personalToken())).retrieve().body(JsonNode.class);
    }

}
