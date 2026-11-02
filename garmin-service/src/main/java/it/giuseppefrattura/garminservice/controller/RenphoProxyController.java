package it.giuseppefrattura.garminservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Forwarding proxy for all calls directed to renpho-service (/api/renpho/**).
 * Only a small allow-list of headers is forwarded upstream. Internal cookies,
 * Authorization from outside the gateway, and host metadata are stripped.
 */
@RestController
@RequestMapping("/api/renpho")
public class RenphoProxyController {

    private static final Set<String> ALLOWED_REQUEST_HEADERS = Set.of(
            "accept", "accept-language", "content-type", "if-match", "if-none-match"
    );
    private static final Set<String> ALLOWED_RESPONSE_HEADERS = Set.of(
            "content-type", "cache-control", "etag", "last-modified", "vary"
    );

    private final RestTemplate restTemplate;
    private final String renphoServiceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RenphoProxyController(
            @Value("${garmin.renpho.url:http://renpho-service:8082}") String renphoServiceUrl) {
        this.renphoServiceUrl = renphoServiceUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    RenphoProxyController(String renphoServiceUrl, RestTemplate restTemplate) {
        this.renphoServiceUrl = renphoServiceUrl;
        this.restTemplate = restTemplate;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyRenphoRequest(
            @RequestBody(required = false) byte[] body,
            @NonNull HttpMethod method,
            HttpServletRequest request) {

        String targetUrl = buildTargetUrl(request);
        HttpHeaders headers = copyAllowedRequestHeaders(request);
        HttpEntity<byte[]> httpEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    targetUrl, method, httpEntity, byte[].class);
            HttpHeaders filtered = filterAllowedResponseHeaders(upstream.getHeaders());
            return ResponseEntity.status(upstream.getStatusCode())
                    .headers(filtered)
                    .body(upstream.getBody());
        } catch (HttpStatusCodeException e) {
            return buildStatusExceptionResponse(e);
        } catch (Exception e) {
            log.warn("Renpho proxy failure: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(writeErrorJson("Renpho service is unavailable"));
        }
    }

    private String buildTargetUrl(HttpServletRequest request) {
        String path = request.getRequestURI();
        String targetUrl = renphoServiceUrl + (path != null ? path : "");
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            targetUrl += "?" + queryString;
        }
        return targetUrl;
    }

    private HttpHeaders copyAllowedRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (request.getHeaderNames() == null) {
            return headers;
        }
        List<String> headerNames = Collections.list(request.getHeaderNames());
        for (String headerName : headerNames) {
            if (ALLOWED_REQUEST_HEADERS.contains(headerName.toLowerCase())) {
                headers.add(headerName, request.getHeader(headerName));
            }
        }
        return headers;
    }

    private HttpHeaders filterAllowedResponseHeaders(HttpHeaders sourceHeaders) {
        HttpHeaders filtered = new HttpHeaders();
        if (sourceHeaders == null) {
            return filtered;
        }
        sourceHeaders.forEach((name, values) -> copyAllowedHeaderValues(filtered, name, values));
        return filtered;
    }

    private void copyAllowedHeaderValues(HttpHeaders target, String name, List<String> values) {
        if (name == null || values == null || !ALLOWED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
            return;
        }
        for (String val : values) {
            if (val != null) {
                target.add(name, val);
            }
        }
    }

    private ResponseEntity<byte[]> buildStatusExceptionResponse(HttpStatusCodeException e) {
        HttpHeaders filtered = filterAllowedResponseHeaders(e.getResponseHeaders());
        byte[] body0 = e.getResponseBodyAsByteArray();
        if (body0 != null && body0.length > 0) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(filtered)
                    .body(body0);
        }
        if (filtered.getContentType() == null) {
            filtered.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.status(e.getStatusCode())
                .headers(filtered)
                .body(writeErrorJson("Upstream Renpho error"));
    }

    private byte[] writeErrorJson(String detail) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("detail", detail);
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"detail\":\"proxy failure\"}".getBytes();
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RenphoProxyController.class);
}
