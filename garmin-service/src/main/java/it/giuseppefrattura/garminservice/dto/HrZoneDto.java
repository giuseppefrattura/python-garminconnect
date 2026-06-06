package it.giuseppefrattura.garminservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps a single HR zone entry from the proxy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HrZoneDto(
        @JsonProperty("zoneNumber") Integer zoneNumber,
        @JsonProperty("secsInZone") Double secsInZone
) {}
