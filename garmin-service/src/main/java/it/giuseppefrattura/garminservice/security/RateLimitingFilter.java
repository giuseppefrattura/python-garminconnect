package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.giuseppefrattura.garminservice.service.CustomMetricsService;

/**
 * Filter that applies tiered IP-based rate limiting to protect the application against DoS and brute-force attacks.
 * <p>
 * Limits:
 * - /login: 20 req/min
 * - /sync: 6 req/min
 * - General endpoints: 180 req/min
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final int MAX_LOGIN_REQUESTS_PER_MINUTE = 20;
    private static final int MAX_SYNC_REQUESTS_PER_MINUTE = 6;
    private static final int MAX_GENERAL_REQUESTS_PER_MINUTE = 180;
    private static final int MAX_TRACKER_ENTRIES = 10_000;
    private static final long WINDOW_MS = 60_000L;

    private final Set<String> trustedProxies;
    private final Map<String, RequestTracker> trackerMap = new ConcurrentHashMap<>();
    private final CustomMetricsService customMetricsService;

    public RateLimitingFilter(
            @Value("${security.trusted-proxies:127.0.0.1,::1}") String trustedProxiesCsv,
            Optional<CustomMetricsService> customMetricsServiceOpt) {
        Set<String> proxies = new HashSet<>();
        if (trustedProxiesCsv != null && !trustedProxiesCsv.isBlank()) {
            for (String part : trustedProxiesCsv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    proxies.add(trimmed);
                }
            }
        }
        this.trustedProxies = Collections.unmodifiableSet(proxies);
        this.customMetricsService = (customMetricsServiceOpt != null && customMetricsServiceOpt.isPresent())
                ? customMetricsServiceOpt.get() : null;
    }

    public RateLimitingFilter(String trustedProxiesCsv) {
        this(trustedProxiesCsv, Optional.empty());
    }

    private static class RequestTracker {
        long windowStartTimestamp = System.currentTimeMillis();
        AtomicInteger count = new AtomicInteger(0);
    }

    private final AtomicLong lastEvictionTimestamp = new AtomicLong(0);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
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
            if (existing == null || (now - existing.windowStartTimestamp) > WINDOW_MS) {
                RequestTracker fresh = new RequestTracker();
                fresh.count.set(1);
                return fresh;
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (trackerMap.size() > MAX_TRACKER_ENTRIES) {
            evictStaleEntries(now);
        }

        if (tracker.count.get() > limit) {
            log.warn("Rate limit exceeded for IP: {} on category: {} (count: {} / limit: {})",
                    ip, category, tracker.count.get(), limit);
            if (customMetricsService != null) {
                customMetricsService.incrementRateLimitHits(category);
            }
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Troppe richieste\",\"message\":\"Limite di richieste superato. Riprova tra 60 secondi.\",\"status\":429}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void evictStaleEntries(long now) {
        long last = lastEvictionTimestamp.get();
        if (now - last > 30_000L && lastEvictionTimestamp.compareAndSet(last, now)) {
            trackerMap.entrySet().removeIf(e -> (now - e.getValue().windowStartTimestamp) > WINDOW_MS);
        }
    }

    private boolean isTrustedProxy(String remote) {
        if (remote == null) return false;
        if (trustedProxies.contains("*") || trustedProxies.contains(remote)) {
            return true;
        }
        return remote.equals("127.0.0.1") || remote.equals("0:0:0:0:0:0:0:1") || remote.equals("::1");
    }

    private String getClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || !isTrustedProxy(remote)) {
            return remote != null ? remote : "unknown";
        }
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return remote;
    }
}
