package it.giuseppefrattura.garminservice.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Global exception handler for all REST controllers.
 * <p>
 * Returns a uniform JSON error envelope:
 * {@code {"status": "error", "detail": "..."}}
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Garmin proxy is unreachable (after retry exhaustion).
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleProxyUnreachable(ResourceAccessException ex) {
        log.error("Garmin proxy unreachable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "status", "error",
                        "detail", "Garmin proxy is unreachable. Please try again later."
                ));
    }

    /**
     * Other HTTP errors from the proxy (4xx/5xx responses).
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> handleRestClientError(RestClientException ex) {
        log.error("Garmin proxy request failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "status", "error",
                        "detail", "Garmin proxy request failed: " + ex.getMessage()
                ));
    }

    /**
     * Catch-all for any unhandled exception.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", "error",
                        "detail", "An unexpected error occurred. Please contact support."
                ));
    }
}
