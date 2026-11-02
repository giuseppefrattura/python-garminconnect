package it.giuseppefrattura.garminservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TotpServiceTest {

    // Base32 encoding of ASCII "12345678901234567890" (RFC 6238 test seed)
    private static final String RFC_SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void generateSecretProduces32CharBase32String() {
        TotpService service = new TotpService();
        String secret = service.generateSecret();
        assertNotNull(secret);
        assertEquals(32, secret.length());
        assertTrue(secret.matches("[A-Z2-7]+"));
    }

    @Test
    void springUsesConfiguredAlgorithmAndRejectsUnsupportedValues() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("security.totp.algorithm", "SHA256")));
            context.register(TotpService.class);
            context.refresh();
            assertTrue(context.getBean(TotpService.class).generateOtpAuthUri("test", RFC_SEED_BASE32).contains("algorithm=SHA256"));
        }
        assertThrows(IllegalArgumentException.class, () -> new TotpService("SHA512"));
    }

    @Test
    void generateOtpAuthUriKeepsLegacySha1ByDefault() {
        TotpService service = new TotpService();
        String uri = service.generateOtpAuthUri("giuseppe", RFC_SEED_BASE32);
        assertTrue(uri.contains("secret=" + RFC_SEED_BASE32));
        assertTrue(uri.contains("algorithm=SHA1"));
    }

    @Test
    void verifiesOnlyTheEnrolledAlgorithmWithinWindow() {
        TotpService service = new TotpService();
        long epochSeconds = 59L; // window = 1
        String sha256Code = service.generateCodeForWindow(RFC_SEED_BASE32, 1L, "HmacSHA256");
        String sha1Code = service.generateCodeForWindow(RFC_SEED_BASE32, 1L, "HmacSHA1");

        // RFC 6238 Appendix B: at T=59s (window 1), 6-digit SHA1 TOTP is 287082
        assertEquals("287082", sha1Code);
        assertTrue(service.verifyCodeAtTime(RFC_SEED_BASE32, sha256Code, "SHA256", epochSeconds));
        assertTrue(service.verifyCodeAtTime(RFC_SEED_BASE32, sha1Code, "SHA1", epochSeconds));
        assertFalse(service.verifyCodeAtTime(RFC_SEED_BASE32, sha1Code, "SHA256", epochSeconds));
        assertFalse(service.verifyCodeAtTime(RFC_SEED_BASE32, sha256Code, "SHA1", epochSeconds));
        // Drift tolerance (+1 window = 89s)
        assertTrue(service.verifyCodeAtTime(RFC_SEED_BASE32, sha256Code, "SHA256", epochSeconds + 30L));
        // Outside window (+2 windows = 120s)
        assertFalse(service.verifyCodeAtTime(RFC_SEED_BASE32, sha256Code, "SHA256", epochSeconds + 60L));
    }

    @Test
    void verifyCodeRejectsInvalidInputs() {
        TotpService service = new TotpService();
        assertFalse(service.verifyCode(null, "123456"));
        assertFalse(service.verifyCode(RFC_SEED_BASE32, null));
        assertFalse(service.verifyCode(RFC_SEED_BASE32, "12345"));
        assertFalse(service.verifyCode(RFC_SEED_BASE32, "abcdef"));
    }
}

