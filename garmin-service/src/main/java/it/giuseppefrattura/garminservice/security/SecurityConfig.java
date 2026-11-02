package it.giuseppefrattura.garminservice.security;

import it.giuseppefrattura.garminservice.service.CustomMetricsService;
import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Spring Security configuration class with Defense-in-Depth hardening.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String LOGIN_URL = "/login";
    private static final String LOGIN_FAILURE_URL = "/login?error=true";

    private static final String CSP_POLICY =
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
            "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
            "font-src 'self' https://fonts.gstatic.com data:; " +
            "img-src 'self' data:; " +
            "connect-src 'self'; " +
            "worker-src 'self'; " +
            "frame-ancestors 'none'; " +
            "base-uri 'self'; " +
            "form-action 'self'";

    private final ApiKeyFilter apiKeyFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final MetricsAuthFilter metricsAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final TotpAuthenticationProvider authenticationProvider;
    private final CustomMetricsService customMetricsService;

    public SecurityConfig(ApiKeyFilter apiKeyFilter,
                          RateLimitingFilter rateLimitingFilter,
                          MetricsAuthFilter metricsAuthFilter,
                          CustomUserDetailsService userDetailsService,
                          TotpAuthenticationProvider authenticationProvider,
                          Optional<CustomMetricsService> customMetricsServiceOpt) {
        this.apiKeyFilter = apiKeyFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.metricsAuthFilter = metricsAuthFilter;
        this.userDetailsService = userDetailsService;
        this.authenticationProvider = authenticationProvider;
        this.customMetricsService = (customMetricsServiceOpt != null && customMetricsServiceOpt.isPresent())
                ? customMetricsServiceOpt.get() : null;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(authenticationProvider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .authenticationProvider(authenticationProvider)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfRequestHandler)
                .requireCsrfProtectionMatcher(apiKeyFilter::requiresCsrfProtection)
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(content -> {})
                .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                    .preload(true)
                )
                .contentSecurityPolicy(csp -> csp.policyDirectives(CSP_POLICY))
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(
                        "geolocation=(), microphone=(), camera=(), payment=()"))
            )
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.migrateSession())
                .maximumSessions(3)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    LOGIN_URL, "/login.html", "/favicon.png", "/favicon.ico",
                    "/manifest.webmanifest", "/sw.js", "/icons/**",
                    "/css/**", "/js/**", "/actuator/health", "/actuator/health/**", "/actuator/info",
                    "/actuator/prometheus"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .authenticationDetailsSource(TotpAuthenticationProvider.Details::new)
                .loginPage(LOGIN_URL)
                .defaultSuccessUrl("/", true)
                .failureUrl(LOGIN_FAILURE_URL)
                .successHandler((request, response, authentication) -> {
                    userDetailsService.handleSuccessfulLogin(authentication.getName());
                    response.sendRedirect("/");
                })
                .failureHandler((request, response, exception) -> {
                    String username = request.getParameter("username");
                    if (username != null) {
                        userDetailsService.handleFailedLogin(username);
                    }
                    if (customMetricsService != null) {
                        customMetricsService.incrementLoginFailure();
                    }
                    response.sendRedirect(LOGIN_FAILURE_URL);
                })
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl(LOGIN_URL)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .permitAll()
            );

        // Add rate limiting filter, API key filter, metrics auth filter, and eager CSRF cookie filter
        http.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(metricsAuthFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Forces eager evaluation of the deferred Spring Security 6 CsrfToken so that
     * the XSRF-TOKEN cookie is sent on initial GET responses (/login and /).
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                @NonNull HttpServletRequest request,
                @NonNull HttpServletResponse response,
                @NonNull FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
