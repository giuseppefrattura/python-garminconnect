package it.giuseppefrattura.garminservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Service implementing RFC 6238 TOTP (Time-Based One-Time Password) algorithm.
 * Defaults to HMAC-SHA1 for compatibility with existing authenticator registrations.
 * HMAC-SHA256 can be configured for authenticator apps that support it.
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int DIGITS_MODULO = 1_000_000;
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String DEFAULT_URI_ALGO = "SHA1";

    private final SecureRandom secureRandom = new SecureRandom();
    private final String uriAlgorithm;

    public TotpService() {
        this(DEFAULT_URI_ALGO);
    }

    @Autowired
    public TotpService(@Value("${security.totp.algorithm:SHA1}") String uriAlgorithm) {
        this.uriAlgorithm = (uriAlgorithm == null || uriAlgorithm.isBlank())
                ? DEFAULT_URI_ALGO
                : uriAlgorithm.trim().toUpperCase();
        if (!List.of("SHA1", "SHA256").contains(this.uriAlgorithm)) {
            throw new IllegalArgumentException("Unsupported TOTP algorithm: " + this.uriAlgorithm);
        }
    }

    /**
     * Generate a new 160-bit (20 byte) random Base32 encoded secret key.
     */
    public String generateSecret() {
        byte[] buffer = new byte[20];
        secureRandom.nextBytes(buffer);
        return encodeBase32(buffer);
    }

    /**
     * Generate otpauth URI for QR Code scanning.
     */
    public String generateOtpAuthUri(String username, String secret) {
        String issuer = "GarminFit";
        String encodedIssuer = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        String encodedAccount = URLEncoder.encode(username, StandardCharsets.UTF_8);
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=%s&digits=6&period=30",
                encodedIssuer, encodedAccount, secret, encodedIssuer, uriAlgorithm);
    }

    /**
     * Verify a 6-digit TOTP code against a secret key with a window tolerance of +/- 1 time step.
     */
    public boolean verifyCode(String secret, String inputCode) {
        return verifyCode(secret, inputCode, uriAlgorithm);
    }

    public String getEnrollmentAlgorithm() { return uriAlgorithm; }

    public boolean verifyCode(String secret, String inputCode, String algorithm) {
        return verifyCodeAtTime(secret, inputCode, algorithm, System.currentTimeMillis() / 1000L);
    }

    boolean verifyCodeAtTime(String secret, String inputCode, String algorithm, long epochSeconds) {
        if (secret == null || inputCode == null || algorithm == null || !List.of("SHA1", "SHA256").contains(algorithm)) {
            return false;
        }

        String cleanedCode = inputCode.trim();
        if (cleanedCode.length() != CODE_DIGITS || !cleanedCode.matches("\\d+")) {
            return false;
        }

        long currentWindow = epochSeconds / TIME_STEP_SECONDS;
        String macAlgorithm = "SHA1".equals(algorithm) ? "HmacSHA1" : "HmacSHA256";

        // RFC 6238 permits HMAC-SHA1; retain it for existing registrations.
        for (int i = -1; i <= 1; i++) {
            long window = currentWindow + i;
            String expectedCode = generateCodeForWindow(secret, window, macAlgorithm);
            if (expectedCode != null && expectedCode.equals(cleanedCode)) {
                return true;
            }
        }
        return false;
    }

    String generateCodeForWindow(String secret, long window, String macAlgorithm) {
        try {
            byte[] keyBytes = decodeBase32(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(window).array();

            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(keyBytes, macAlgorithm));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % DIGITS_MODULO;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Failed to calculate TOTP hash for window: {}", window, e);
            return null;
        }
    }

    // --- Base32 Utility Methods ---

    private String encodeBase32(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                result.append(BASE32_CHARS.charAt(index));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            result.append(BASE32_CHARS.charAt(index));
        }
        return result.toString();
    }

    private byte[] decodeBase32(String base32) {
        String upper = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] result = new byte[upper.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int count = 0;

        for (char c : upper.toCharArray()) {
            int val = BASE32_CHARS.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Illegal character in Base32 string: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[count++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return Arrays.copyOf(result, count);
    }
}
