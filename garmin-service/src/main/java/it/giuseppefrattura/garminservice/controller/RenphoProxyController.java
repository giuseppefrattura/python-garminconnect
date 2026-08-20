package it.giuseppefrattura.garminservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;

/**
 * Controller that proxies Renpho requests to the internal FastAPI service.
 * This secures the Renpho service endpoints under Spring Security session.
 */
@RestController
@RequestMapping("/api/renpho")
public class RenphoProxyController {

    private final RestTemplate restTemplate;
    private final String renphoServiceUrl;

    public RenphoProxyController(
            @Value("${garmin.renpho.url:http://renpho-service:8082}") String renphoServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.renphoServiceUrl = renphoServiceUrl;
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxyRenphoRequest(
            @RequestBody(required = false) byte[] body,
            @NonNull HttpMethod method,
            HttpServletRequest request) {

        String path = request.getRequestURI();
        // Construct target URL (e.g. http://renpho-service:8082/api/renpho/measurements)
        String targetUrl = renphoServiceUrl + (path != null ? path : "");
        
        // Append query parameters if present
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isBlank()) {
            targetUrl += "?" + queryString;
        }

        // Copy request headers
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            // Avoid copying host and content-length headers since they are set by RestTemplate
            if (!headerName.equalsIgnoreCase("host") && !headerName.equalsIgnoreCase("content-length")) {
                headers.add(headerName, request.getHeader(headerName));
            }
        });

        HttpEntity<byte[]> httpEntity = new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(targetUrl, Objects.requireNonNull(method, "HttpMethod must not be null"), httpEntity, byte[].class);
        } catch (HttpStatusCodeException e) {
            HttpHeaders responseHeaders = e.getResponseHeaders();
            return ResponseEntity.status(e.getStatusCode())
                    .headers(responseHeaders != null ? responseHeaders : new HttpHeaders())
                    .body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            String errorMsg = "Proxy Error: " + (e.getMessage() != null ? e.getMessage() : "Unknown error");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorMsg.getBytes(StandardCharsets.UTF_8));
        }
    }
}
