package it.giuseppefrattura.garminservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

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
            HttpMethod method,
            HttpServletRequest request) {

        String path = request.getRequestURI();
        // Construct target URL (e.g. http://renpho-service:8082/api/renpho/measurements)
        String targetUrl = renphoServiceUrl + path;
        
        // Append query parameters if present
        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
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
            return restTemplate.exchange(targetUrl, method, httpEntity, byte[].class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Proxy Error: " + e.getMessage()).getBytes());
        }
    }
}
