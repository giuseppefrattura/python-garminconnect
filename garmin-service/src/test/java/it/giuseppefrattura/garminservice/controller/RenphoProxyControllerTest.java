package it.giuseppefrattura.garminservice.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class RenphoProxyControllerTest {

    @Mock
    private RestTemplate restTemplate;

    @Captor
    private ArgumentCaptor<HttpEntity<byte[]>> entityCaptor;

    private RenphoProxyController proxyController;

    @BeforeEach
    void setUp() {
        proxyController = new RenphoProxyController("http://renpho-mock:8082", restTemplate);
    }

    @Test
    void testProxyRenphoRequest_SuccessfulForwardAndHeaderFiltering() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/renpho/measurements");
        request.addHeader("Accept", "application/json");
        request.addHeader("Authorization", "Bearer secret-token"); // Disallowed header: must be stripped!
        request.addHeader("Cookie", "session=123"); // Disallowed header: must be stripped!

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        responseHeaders.set("Set-Cookie", "session=abc"); // Disallowed response header: must be stripped!
        responseHeaders.set("Cache-Control", "no-cache"); // Allowed response header

        byte[] mockBody = "{\"measurements\":[]}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<byte[]> upstreamResponse = new ResponseEntity<>(mockBody, responseHeaders, HttpStatus.OK);

        when(restTemplate.exchange(
                eq("http://renpho-mock:8082/api/renpho/measurements"),
                eq(HttpMethod.GET),
                any(),
                eq(byte[].class)
        )).thenReturn(upstreamResponse);

        ResponseEntity<byte[]> result = proxyController.proxyRenphoRequest(null, HttpMethod.GET, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("{\"measurements\":[]}", new String(result.getBody(), StandardCharsets.UTF_8));

        // Check response header filtering
        HttpHeaders resultHeaders = result.getHeaders();
        assertEquals(MediaType.APPLICATION_JSON, resultHeaders.getContentType());
        assertEquals(List.of("no-cache"), resultHeaders.get("Cache-Control"));
        assertNull(resultHeaders.get("Set-Cookie"));

        // Capture request entity sent to upstream
        verify(restTemplate).exchange(anyString(), any(HttpMethod.class), entityCaptor.capture(), eq(byte[].class));
        HttpEntity<byte[]> captured = entityCaptor.getValue();
        assertNotNull(captured);
        assertTrue(captured.getHeaders().containsKey("Accept"));
        assertFalse(captured.getHeaders().containsKey("Authorization"));
        assertFalse(captured.getHeaders().containsKey("Cookie"));
    }

    @Test
    void testProxyRenphoRequest_UpstreamDown_Returns502() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/renpho/measurements");

        when(restTemplate.exchange(
                anyString(),
                any(HttpMethod.class),
                any(HttpEntity.class),
                eq(byte[].class)
        )).thenThrow(new ResourceAccessException("Connection refused"));

        ResponseEntity<byte[]> result = proxyController.proxyRenphoRequest(null, HttpMethod.GET, request);

        assertEquals(HttpStatus.BAD_GATEWAY, result.getStatusCode());
        assertNotNull(result.getBody());
        String body = new String(result.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Renpho service is unavailable"));
        assertTrue(body.contains("error"));
    }
}
