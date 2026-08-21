package it.giuseppefrattura.garminservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Service implementing RFC 6238 TOTP (Time-Based One-Time Password) algorithm
 * compatible with Google Authenticator, Microsoft Authenticator, Authy, and 1Password.
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);
    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom secureRandom = new SecureRandom();

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
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
                encodedIssuer, encodedAccount, secret, encodedIssuer);
    }

    /**
     * Verify a 6-digit TOTP code against a secret key with a window tolerance of +/- 1 time step.
     */
    public boolean verifyCode(String secret, String inputCode) {
        if (secret == null || inputCode == null) {
            return false;
        }

        String cleanedCode = inputCode.trim();
        if (cleanedCode.length() != CODE_DIGITS || !cleanedCode.matches("\\d+")) {
            return false;
        }

        long currentWindow = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;

        // Check window -1, 0, +1 for clock drift tolerance
        for (int i = -1; i <= 1; i++) {
            long window = currentWindow + i;
            String expectedCode = generateCodeForWindow(secret, window);
            if (expectedCode != null && expectedCode.equals(cleanedCode)) {
                return true;
            }
        }
        return false;
    }

    private String generateCodeForWindow(String secret, long window) {
        try {
            byte[] keyBytes = decodeBase32(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(window).array();

            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%06d", otp);
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
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
