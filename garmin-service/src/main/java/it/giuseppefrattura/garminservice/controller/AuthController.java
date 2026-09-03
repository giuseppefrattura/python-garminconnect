package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.User;
import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import it.giuseppefrattura.garminservice.service.TotpService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CustomUserDetailsService userDetailsService;
    private final TotpService totpService;

    public AuthController(CustomUserDetailsService userDetailsService, TotpService totpService) {
        this.userDetailsService = userDetailsService;
        this.totpService = totpService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> getAuthStatus() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        String username = auth.getName();
        Optional<User> optUser = userDetailsService.findUser(username);

        boolean totpEnabled = false;
        String role = "ROLE_USER";
        if (optUser.isPresent()) {
            User user = optUser.get();
            totpEnabled = user.isTotpEnabled();
            if (user.getRole() != null) {
                role = user.getRole();
            }
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "username", username,
                "role", role,
                "totpEnabled", totpEnabled
        ));
    }

    @PostMapping("/2fa/setup")
    public ResponseEntity<?> setup2Fa() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Non autenticato"));
        }

        String username = auth.getName();
        String newSecret = totpService.generateSecret();
        String qrUri = totpService.generateOtpAuthUri(username, newSecret);

        return ResponseEntity.ok(Map.of(
                "secret", newSecret,
                "qrUri", qrUri
        ));
    }

    @PostMapping("/2fa/enable")
    public ResponseEntity<?> enable2Fa(@RequestBody Map<String, String> payload) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Non autenticato"));
        }

        String username = auth.getName();
        String secret = payload.get("secret");
        String code = payload.get("code");

        if (secret == null || code == null || !totpService.verifyCode(secret, code)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Codice di verifica non valido. Assicurati che l'orario del telefono sia sincronizzato."
            ));
        }

        Optional<User> optUser = userDetailsService.findUser(username);
        if (optUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = optUser.get();
        user.setTotpSecret(secret);
        user.setTotpEnabled(true);
        userDetailsService.saveUser(user);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Autenticazione a due fattori (2FA) attivata con successo!"
        ));
    }

    @PostMapping("/2fa/disable")
    public ResponseEntity<?> disable2Fa(@RequestBody Map<String, String> payload) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Non autenticato"));
        }

        String username = auth.getName();
        Optional<User> optUser = userDetailsService.findUser(username);
        if (optUser.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = optUser.get();
        if (!user.isTotpEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("message", "2FA non attiva"));
        }

        String code = payload.get("code");
        if (code == null || !totpService.verifyCode(user.getTotpSecret(), code)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Codice OTP non valido. Impossibile disattivare la 2FA."
            ));
        }

        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        userDetailsService.saveUser(user);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Autenticazione a due fattori disattivata."
        ));
    }
}
