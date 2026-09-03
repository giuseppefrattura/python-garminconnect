package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.User;
import it.giuseppefrattura.garminservice.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${DASHBOARD_USER:}")
    private String defaultUsername;

    @Value("${DASHBOARD_PASSWORD:}")
    private String defaultPassword;

    public CustomUserDetailsService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void initDefaultUser() {
        if (defaultUsername == null || defaultUsername.isBlank()
                || defaultPassword == null || defaultPassword.isBlank()) {
            log.warn("DASHBOARD_USER/DASHBOARD_PASSWORD not set; skipping default admin bootstrap. "
                    + "Create a user manually or set both env vars before first boot.");
            return;
        }
        try {
            if (!userRepository.existsByUsername(defaultUsername)) {
                log.info("Creating default database administrator user: {}", defaultUsername);
                String hash = passwordEncoder.encode(defaultPassword);
                User admin = new User(defaultUsername, hash, "ROLE_ADMIN");
                userRepository.save(admin);
                log.info("Default user {} initialized with BCrypt password hash.", defaultUsername);
            }
        } catch (Exception e) {
            log.warn("Could not check/initialize default user (database may be initializing): {}", e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (!user.isAccountNonLocked()) {
            throw new LockedException("Account is locked due to too many failed attempts until " + user.getLockoutUntil());
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority(user.getRole())))
                .accountLocked(!user.isAccountNonLocked())
                .build();
    }

    @Transactional
    public void handleFailedLogin(String username) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isPresent()) {
            User user = optUser.get();
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            user.setUpdatedAt(LocalDateTime.now());

            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setLockoutUntil(LocalDateTime.now().plusMinutes(LOCKOUT_DURATION_MINUTES));
                log.warn("User {} has exceeded max failed login attempts ({}). Account locked for {} minutes.",
                        username, attempts, LOCKOUT_DURATION_MINUTES);
            } else {
                log.warn("Failed login attempt {}/{} for user {}", attempts, MAX_FAILED_ATTEMPTS, username);
            }
            userRepository.save(user);
        }
    }

    @Transactional
    public void handleSuccessfulLogin(String username) {
        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isPresent()) {
            User user = optUser.get();
            if (user.getFailedLoginAttempts() > 0 || user.getLockoutUntil() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockoutUntil(null);
                user.setUpdatedAt(LocalDateTime.now());
                userRepository.save(user);
                log.info("Reset failed login attempts for user {}", username);
            }
        }
    }

    public Optional<User> findUser(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public void saveUser(User user) {
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }
}
