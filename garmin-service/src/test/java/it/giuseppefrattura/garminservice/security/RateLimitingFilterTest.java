package it.giuseppefrattura.garminservice.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitingFilterTest {

    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        // Trusted proxies: 127.0.0.1, 10.0.0.1
        filter = new RateLimitingFilter("127.0.0.1, 10.0.0.1");
    }

    @Test
    void testUnderLimitRequest_ProceedsFilterChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/strength-workouts");
        request.setRemoteAddr("192.168.1.50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void testLoginRateLimit_BlocksAfterMaxAttempts() throws ServletException, IOException {
        String clientIp = "192.168.1.100";

        // Perform 20 login attempts (allowed)
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
            req.setRemoteAddr(clientIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilterInternal(req, res, chain);
            assertEquals(200, res.getStatus());
        }

        // 21st attempt must be blocked with 429
        MockHttpServletRequest blockedReq = new MockHttpServletRequest("POST", "/login");
        blockedReq.setRemoteAddr(clientIp);
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();

        filter.doFilterInternal(blockedReq, blockedRes, blockedChain);

        assertEquals(429, blockedRes.getStatus());
        assertTrue(blockedRes.getContentAsString().contains("Troppe richieste"));
        assertNull(blockedChain.getRequest());
    }

    @Test
    void testSyncRateLimit_BlocksAfterMaxAttempts() throws ServletException, IOException {
        String clientIp = "192.168.1.101";

        // Perform 6 sync attempts (allowed)
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/health/sync");
            req.setRemoteAddr(clientIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilterInternal(req, res, chain);
            assertEquals(200, res.getStatus());
        }

        // 7th attempt must be blocked with 429
        MockHttpServletRequest blockedReq = new MockHttpServletRequest("POST", "/api/health/sync");
        blockedReq.setRemoteAddr(clientIp);
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();

        filter.doFilterInternal(blockedReq, blockedRes, blockedChain);

        assertEquals(429, blockedRes.getStatus());
        assertTrue(blockedRes.getContentAsString().contains("Troppe richieste"));
        assertNull(blockedChain.getRequest());
    }

    @Test
    void testTrustedProxy_ExtractsClientIpFromXForwardedFor() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workouts");
        req.setRemoteAddr("127.0.0.1"); // Trusted proxy
        req.addHeader("X-Forwarded-For", "203.0.113.195, 127.0.0.1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);
        assertEquals(200, res.getStatus());
    }

    @Test
    void testUntrustedProxy_IgnoresXForwardedFor() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workouts");
        req.setRemoteAddr("8.8.8.8"); // Untrusted proxy
        req.addHeader("X-Forwarded-For", "203.0.113.195");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);
        assertEquals(200, res.getStatus());
    }
}
