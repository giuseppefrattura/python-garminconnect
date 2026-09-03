package it.giuseppefrattura.garminservice.security;

import it.giuseppefrattura.garminservice.service.CustomMetricsService;
import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

import java.util.Optional;

/**
 * Spring Security configuration class with Defense-in-Depth hardening.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
    private final CustomMetricsService customMetricsService;

    public SecurityConfig(ApiKeyFilter apiKeyFilter,
                          RateLimitingFilter rateLimitingFilter,
                          MetricsAuthFilter metricsAuthFilter,
                          CustomUserDetailsService userDetailsService,
                          Optional<CustomMetricsService> customMetricsServiceOpt) {
        this.apiKeyFilter = apiKeyFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.metricsAuthFilter = metricsAuthFilter;
        this.userDetailsService = userDetailsService;
        this.customMetricsService = (customMetricsServiceOpt != null && customMetricsServiceOpt.isPresent())
                ? customMetricsServiceOpt.get() : null;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(
                        "/api/**",
                        "/actuator/**",
                        "/login",
                        "/logout"
                )
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
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
            .userDetailsService(userDetailsService)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/login", "/login.html", "/favicon.png", "/favicon.ico",
                    "/manifest.webmanifest", "/sw.js", "/icons/**",
                    "/css/**", "/js/**", "/actuator/health", "/actuator/health/**", "/actuator/info",
                    "/actuator/prometheus"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error=true")
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
                    response.sendRedirect("/login?error=true");
                })
                .permitAll()
            )
            .oauth2Login(oauth -> oauth
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error=true")
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .permitAll()
            );

        // Add rate limiting filter, API key filter, and metrics auth filter before UsernamePasswordAuthenticationFilter
        http.addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(metricsAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
