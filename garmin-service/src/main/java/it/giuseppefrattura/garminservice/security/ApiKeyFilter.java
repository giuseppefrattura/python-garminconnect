package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that validates the X-API-Key header for incoming requests to /api/**.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final String apiKey;

    public ApiKeyFilter(@Value("${garmin.service.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Secure only paths starting with /api
        if (!path.startsWith("/api")) {
            filterChain.doFilter(request, response);
            return;
        }

        // If no API key is configured (empty or blank), bypass security checks.
        if (apiKey == null || apiKey.trim().isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestApiKey = request.getHeader("X-API-Key");
        if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"error\",\"detail\":\"Invalid or missing API Key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
