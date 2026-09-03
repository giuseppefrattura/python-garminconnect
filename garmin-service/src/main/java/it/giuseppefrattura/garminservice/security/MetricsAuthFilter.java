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
        if (path == null || !path.equals("/actuator/prometheus")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow if already authenticated
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated() && !(existingAuth instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Check Bearer token or custom headers
        String authHeader = request.getHeader("Authorization");
        String bearer = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            bearer = authHeader.substring(7).trim();
        }

        String xMetricsToken = request.getHeader("X-Metrics-Token");
        String xApiKey = request.getHeader("X-API-Key");

        boolean authorized = false;

        if (metricsToken != null && !metricsToken.isBlank()) {
            if ((bearer != null && MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8), metricsToken.getBytes(StandardCharsets.UTF_8))) ||
                (xMetricsToken != null && MessageDigest.isEqual(xMetricsToken.getBytes(StandardCharsets.UTF_8), metricsToken.getBytes(StandardCharsets.UTF_8)))) {
                authorized = true;
            }
        }

        if (!authorized && apiKey != null && !apiKey.isBlank()) {
            if ((bearer != null && MessageDigest.isEqual(bearer.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8))) ||
                (xApiKey != null && MessageDigest.isEqual(xApiKey.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8)))) {
                authorized = true;
            }
        }

        if (!authorized && allowInsecureDev && (metricsToken == null || metricsToken.isBlank()) && (apiKey == null || apiKey.isBlank())) {
            authorized = true;
        }

        if (authorized) {
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
}
