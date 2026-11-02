package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyFilterTest {

    @Test
    void safeMethodsDoNotRequireCsrfProtection() {
        ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);
        for (String method : new String[]{"GET", "HEAD", "OPTIONS", "TRACE"}) {
            MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/health/daily");
            assertFalse(filter.requiresCsrfProtection(req), method + " should not require CSRF");
        }
    }

    @Test
    void statelessValidApiKeyRequestToApiDoesNotRequireCsrf() {
        ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/health/sync");
        req.addHeader("X-API-Key", "secret-api-key");

        assertFalse(filter.requiresCsrfProtection(req));
    }

    @Test
    void sessionCookieRequestRequiresCsrfEvenWithApiKeyHeader() {
        ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/health/sync");
        req.addHeader("X-API-Key", "secret-api-key");
        req.setCookies(new Cookie("JSESSIONID", "abc123session"));

        assertTrue(filter.requiresCsrfProtection(req));
    }

    @Test
    void mutatingRequestWithoutValidApiKeyRequiresCsrf() {
        ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);

        MockHttpServletRequest apiNoKey = new MockHttpServletRequest("POST", "/api/auth/2fa/enable");
        assertTrue(filter.requiresCsrfProtection(apiNoKey));

        MockHttpServletRequest apiWrongKey = new MockHttpServletRequest("POST", "/api/auth/2fa/enable");
        apiWrongKey.addHeader("X-API-Key", "wrong-key");
        assertTrue(filter.requiresCsrfProtection(apiWrongKey));

        MockHttpServletRequest loginReq = new MockHttpServletRequest("POST", "/login");
        assertTrue(filter.requiresCsrfProtection(loginReq));
    }

    @Test
    void oauthCallbacksAreNotExemptFromCsrf() {
        ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);
        assertTrue(filter.requiresCsrfProtection(new MockHttpServletRequest("POST", "/login/oauth2/code/apple")));
        assertTrue(filter.requiresCsrfProtection(new MockHttpServletRequest("POST", "/login/oauth2/code/google")));
        assertTrue(filter.requiresCsrfProtection(new MockHttpServletRequest("POST", "/login/oauth2/code/apple/extra")));
    }

    @Test
    void doFilterInternalAuthenticatesValidApiKey() throws Exception {
        SecurityContextHolder.clearContext();
        try {
            ApiKeyFilter filter = new ApiKeyFilter("secret-api-key", false);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workouts");
            req.addHeader("X-API-Key", "secret-api-key");
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, res, chain);

            verify(chain).doFilter(req, res);
            assertNotNull(SecurityContextHolder.getContext().getAuthentication());
            assertEquals("api-user", SecurityContextHolder.getContext().getAuthentication().getName());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

