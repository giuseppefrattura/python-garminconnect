package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding-window Rate Limiter to protect against Brute-Force login attacks
 * and resource exhaustion (DoS) on synchronization endpoints.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final int MAX_LOGIN_REQUESTS_PER_MINUTE = 10;
    private static final int MAX_SYNC_REQUESTS_PER_MINUTE = 6;
    private static final int MAX_GENERAL_REQUESTS_PER_MINUTE = 180;

    private static class RequestTracker {
        long windowStartTimestamp = System.currentTimeMillis();
        AtomicInteger count = new AtomicInteger(0);
    }

    private final Map<String, RequestTracker> trackerMap = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String ip = getClientIp(request);
        String path = request.getRequestURI();
        String method = request.getMethod();

        int limit = MAX_GENERAL_REQUESTS_PER_MINUTE;
        String category = "general";

        if ("/login".equals(path) && "POST".equalsIgnoreCase(method)) {
            limit = MAX_LOGIN_REQUESTS_PER_MINUTE;
            category = "login";
        } else if (path.endsWith("/sync")) {
            limit = MAX_SYNC_REQUESTS_PER_MINUTE;
            category = "sync";
        }

        String trackerKey = category + ":" + ip;
        long now = System.currentTimeMillis();

        RequestTracker tracker = trackerMap.compute(trackerKey, (k, existing) -> {
            if (existing == null || (now - existing.windowStartTimestamp) > 60_000) {
                RequestTracker fresh = new RequestTracker();
                fresh.count.set(1);
                return fresh;
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (tracker.count.get() > limit) {
            log.warn("Rate limit exceeded for IP: {} on category: {} (count: {} / limit: {})",
                    ip, category, tracker.count.get(), limit);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(String.format(
                    "{\"error\":\"Troppe richieste\",\"message\":\"Limite di richieste superato per %s. Riprova tra 60 secondi.\",\"status\":429}",
                    category
            ));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
