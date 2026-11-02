package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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
import java.util.Set;

/**
 * Filter that validates the X-API-Key header for incoming requests to /api/**.
 * <p>
 * Auth is required unless the operator explicitly opts into dev-bypass mode
 * by setting GARMIN_ALLOW_INSECURE_DEV=1 (or security.api-key.bypass-dev=true).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

    private final String apiKey;
    private final boolean bypassInDev;

    public ApiKeyFilter(
            @Value("${garmin.service.api-key:}") String apiKey,
            @Value("${security.api-key.bypass-dev:false}") boolean bypassInDev) {
        this.apiKey = apiKey;
        this.bypassInDev = bypassInDev;
    }

    /**
     * Determines whether CSRF protection is required for the given request.
     * Safe HTTP methods are exempt. Mutating requests to /api/** are exempt ONLY when
     * authenticated via a verified X-API-Key header and no session cookie is present.
     */
    public boolean requiresCsrfProtection(HttpServletRequest request) {
        String method = request.getMethod();
        if (method != null && SAFE_METHODS.contains(method.toUpperCase())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/") && !hasSessionCookie(request) && hasValidApiKey(request)) {
            return false;
        }
        return true;
    }

    public boolean hasValidApiKey(HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        String requestApiKey = request.getHeader("X-API-Key");
        return requestApiKey != null && constantTimeEquals(requestApiKey, apiKey);
    }

    private boolean hasSessionCookie(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if ("JSESSIONID".equalsIgnoreCase(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path == null || !path.startsWith("/api")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated() && !(existingAuth instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            if (bypassInDev) {
                filterChain.doFilter(request, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"error\",\"detail\":\"API key not configured on server\"}");
            return;
        }

        if (!hasValidApiKey(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"error\",\"detail\":\"Invalid or missing API Key\"}");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "api-user", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
