package it.giuseppefrattura.garminservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.giuseppefrattura.garminservice.client.GarminProxyClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalWellnessServiceTest {
    private final GarminProxyClient client = mock(GarminProxyClient.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test void readsWrappedSummariesAndKeepsMissingAndFailedMeasurementsSeparate() throws Exception {
        when(client.personalEnabled()).thenReturn(true);
        when(client.personalGet(anyString(), anyMap())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.contains("steps/daily")) return json.readTree("[{\"calendarDate\":\"2026-10-29\",\"totalSteps\":1234,\"totalDistance\":900}]");
            if (path.contains("dailysummaries")) return json.readTree("{\"data\":[{\"calendarDate\":\"2026-10-29\",\"calories\":{\"burnedTotal\":2100},\"stress\":{\"avgLevel\":25}}]}");
            if (path.contains("dailyHeartRate")) return json.readTree("{\"restingHeartRate\":52}");
            if (path.contains("spo2acclimation")) throw new ResourceAccessException("unavailable");
            return json.readTree("{\"fitnessAge\":32}");
        });
        var result = new PersonalWellnessService(client).load(LocalDate.parse("2026-10-29"), 7);
        var today = result.days().get(6);
        assertEquals("2026-10-23", result.start());
        assertEquals(1234.0, today.steps());
        assertEquals(2100.0, today.calories());
        assertEquals(25.0, today.stress());
        assertEquals(52.0, today.restingHeartRate());
        assertNull(today.spo2());
        assertEquals(32.0, today.fitnessAge());
        assertNull(result.days().get(0).steps());
        assertEquals(7, result.failedRequests().get("SpO₂"));
    }

    @Test void splitsThirtyDayStepsRangeIntoTwoRequests() {
        when(client.personalEnabled()).thenReturn(true);
        new PersonalWellnessService(client).load(LocalDate.parse("2026-10-29"), 30);
        verify(client).personalGet(eq("/usersummary-service/stats/steps/daily/2026-09-30/2026-10-27"), eq(Map.of()));
        verify(client).personalGet(eq("/usersummary-service/stats/steps/daily/2026-10-28/2026-10-29"), eq(Map.of()));
    }
}
