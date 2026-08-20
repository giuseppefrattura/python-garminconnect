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
import java.util.Collections;

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
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Secure only paths starting with /api
        if (path == null || !path.startsWith("/api")) {
            filterChain.doFilter(request, response);
            return;
        }

        // If the request is already authenticated (e.g. via session cookie or OAuth2 login), bypass API key check
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated() && !(existingAuth instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        // If no API key is configured (empty or blank), bypass security checks.
        if (apiKey == null || apiKey.isBlank()) {
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

        // Set authentication in SecurityContextHolder so Spring Security allows the request
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "api-user", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }
}
