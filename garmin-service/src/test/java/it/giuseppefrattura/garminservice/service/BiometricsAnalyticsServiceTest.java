package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.repository.StrengthWorkoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "null"})
class BiometricsAnalyticsServiceTest {

    @Mock
    private PersonalRecordService personalRecordService;

    @Mock
    private StrengthWorkoutRepository workoutRepository;

    @Mock
    private RestTemplate restTemplate;

    private BiometricsAnalyticsService biometricsService;

    @BeforeEach
    void setUp() {
        biometricsService = new BiometricsAnalyticsService(
                "http://mock-renpho:8082",
                personalRecordService,
                workoutRepository,
                restTemplate
        );
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Collections.emptyList()));
    }

    @Test
    void testGetRelativeStrengthOverview_Calculations() {
        PersonalRecordService.TrophyCardDTO benchCard = new PersonalRecordService.TrophyCardDTO("Panca Piana", "Petto");
        benchCard.setEstimated1RmKg(100.0);
        benchCard.setMaxWeightKg(90.0);
        benchCard.setMaxWeightReps(5);

        when(personalRecordService.getTrophyRoomCards()).thenReturn(List.of(benchCard));

        Map<String, Object> result = biometricsService.getRelativeStrengthOverview();

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        assertTrue(result.containsKey("exercises"));
        List<?> exercises = (List<?>) result.get("exercises");
        assertEquals(1, exercises.size());
    }

    @Test
    void testGetBodyRecompositionTrend_Empty() {
        when(workoutRepository.findAllByOrderByWorkoutDateDescWorkoutTimeDesc()).thenReturn(Collections.emptyList());

        Map<String, Object> result = biometricsService.getBodyRecompositionTrend();

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        assertEquals(0, result.get("totalPoints"));
    }
}
