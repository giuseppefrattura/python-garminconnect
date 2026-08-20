package it.giuseppefrattura.garminservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps a single Garmin activity as returned by the proxy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActivityDto(
        @JsonProperty("activityId") Long activityId,
        @JsonProperty("activityName") String activityName,
        @JsonProperty("activityType") ActivityTypeDto activityType,
        @JsonProperty("startTimeLocal") String startTimeLocal,
        @JsonProperty("duration") Double duration,
        @JsonProperty("calories") Integer calories,
        @JsonProperty("averageHR") Integer averageHR,
        @JsonProperty("maxHR") Integer maxHR,
        @JsonProperty("aerobicTrainingEffect") Double aerobicTrainingEffect,
        @JsonProperty("anaerobicTrainingEffect") Double anaerobicTrainingEffect
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityTypeDto(
            @JsonProperty("typeKey") String typeKey
    ) {}
}
