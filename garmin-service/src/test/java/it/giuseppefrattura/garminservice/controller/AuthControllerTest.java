package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.User;
import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import it.giuseppefrattura.garminservice.service.TotpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private TotpService totpService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        authController = new AuthController(userDetailsService, totpService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testGetAuthStatus_Unauthenticated() {
        ResponseEntity<?> response = authController.getAuthStatus();
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("authenticated"));
    }

    @Test
    void testGetAuthStatus_Authenticated() {
        User user = new User("admin", "hashed_pass", "ROLE_ADMIN");
        user.setTotpEnabled(true);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        when(userDetailsService.findUser("admin")).thenReturn(Optional.of(user));

        ResponseEntity<?> response = authController.getAuthStatus();
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("authenticated"));
        assertEquals("admin", body.get("username"));
        assertEquals("ROLE_ADMIN", body.get("role"));
        assertEquals(true, body.get("totpEnabled"));
    }

    @Test
    void testSetup2Fa_Unauthenticated_Returns401() {
        ResponseEntity<?> response = authController.setup2Fa();
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void testSetup2Fa_Authenticated_ReturnsSecretAndQrUri() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("john", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        when(totpService.generateSecret()).thenReturn("SECRET123");
        when(totpService.generateOtpAuthUri("john", "SECRET123")).thenReturn("otpauth://totp/Garmin:john?secret=SECRET123");

        ResponseEntity<?> response = authController.setup2Fa();
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertNotNull(body);
        assertEquals("SECRET123", body.get("secret"));
        assertTrue(body.get("qrUri").contains("SECRET123"));
    }

    @Test
    void testEnable2Fa_ValidCode_EnablesTotp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("john", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        User user = new User("john", "hash", "ROLE_USER");
        when(userDetailsService.findUser("john")).thenReturn(Optional.of(user));
        when(totpService.verifyCode("MYSECRET", "123456")).thenReturn(true);

        ResponseEntity<?> response = authController.enable2Fa(Map.of("secret", "MYSECRET", "code", "123456"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(user.isTotpEnabled());
        assertEquals("MYSECRET", user.getTotpSecret());
        verify(userDetailsService).saveUser(user);
    }

    @Test
    void testEnable2Fa_InvalidCode_ReturnsBadRequest() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("john", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        when(totpService.verifyCode("MYSECRET", "wrong")).thenReturn(false);

        ResponseEntity<?> response = authController.enable2Fa(Map.of("secret", "MYSECRET", "code", "wrong"));
        assertEquals(400, response.getStatusCode().value());
        verify(userDetailsService, never()).saveUser(any());
    }

    @Test
    void testDisable2Fa_ValidCode_DisablesTotp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("john", "pass", List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        User user = new User("john", "hash", "ROLE_USER");
        user.setTotpEnabled(true);
        user.setTotpSecret("ACTIVESECRET");
        when(userDetailsService.findUser("john")).thenReturn(Optional.of(user));
        when(totpService.verifyCode("ACTIVESECRET", "654321")).thenReturn(true);

        ResponseEntity<?> response = authController.disable2Fa(Map.of("code", "654321"));
        assertEquals(200, response.getStatusCode().value());
        assertFalse(user.isTotpEnabled());
        assertNull(user.getTotpSecret());
        verify(userDetailsService).saveUser(user);
    }
}
