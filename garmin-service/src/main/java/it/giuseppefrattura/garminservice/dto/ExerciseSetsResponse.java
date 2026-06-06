package it.giuseppefrattura.garminservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Maps the exercise-sets response from the proxy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExerciseSetsResponse(
        @JsonProperty("exerciseSets") List<ExerciseSetDto> exerciseSets
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExerciseSetDto(
            @JsonProperty("setType") String setType,
            @JsonProperty("repetitionCount") Integer repetitionCount,
            @JsonProperty("weight") Double weight,
            @JsonProperty("exercises") List<ExerciseDto> exercises
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExerciseDto(
            @JsonProperty("category") String category,
            @JsonProperty("name") String name
    ) {}
}
