package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Filter that protects /actuator/prometheus endpoint.
 * Allows access if:
 * 1. An authenticated session exists (e.g. admin logged into dashboard).
 * 2. Authorization header matches Bearer <METRICS_BEARER_TOKEN>.
 * 3. X-Metrics-Token or X-API-Key matches the configured token or API key.
 * 4. Token is not configured and allowInsecureDev is true.
 */
@Component
public class MetricsAuthFilter extends OncePerRequestFilter {

    private final String metricsToken;
    private final String apiKey;
    private final boolean allowInsecureDev;

    public MetricsAuthFilter(
            @Value("${monitoring.metrics-token:}") String metricsToken,
            @Value("${garmin.service.api-key:}") String apiKey,
            @Value("${security.api-key.bypass-dev:false}") boolean allowInsecureDev) {
        this.metricsToken = metricsToken;
        this.apiKey = apiKey;
        this.allowInsecureDev = allowInsecureDev;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!"/actuator/prometheus".equals(path) || isSessionAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isTokenAuthorized(request)) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "metrics-scraper",
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_ACTUATOR"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid Bearer token or metrics key required for /actuator/prometheus\",\"status\":401}");
    }

    private boolean isSessionAuthenticated() {
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        return existingAuth != null
                && existingAuth.isAuthenticated()
                && !(existingAuth instanceof AnonymousAuthenticationToken);
    }

    private boolean isTokenAuthorized(HttpServletRequest request) {
        String bearer = extractBearerToken(request.getHeader("Authorization"));
        String xMetricsToken = request.getHeader("X-Metrics-Token");
        String xApiKey = request.getHeader("X-API-Key");

        if (matchesSecret(metricsToken, bearer, xMetricsToken)) {
            return true;
        }
        if (matchesSecret(apiKey, bearer, xApiKey)) {
            return true;
        }
        return allowInsecureDev && isBlank(metricsToken) && isBlank(apiKey);
    }

    private static String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private static boolean matchesSecret(String configuredSecret, String bearerCandidate, String headerCandidate) {
        if (isBlank(configuredSecret)) {
            return false;
        }
        return constantTimeEquals(configuredSecret, bearerCandidate)
                || constantTimeEquals(configuredSecret, headerCandidate);
    }

    private static boolean constantTimeEquals(String expected, String candidate) {
        if (expected == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
