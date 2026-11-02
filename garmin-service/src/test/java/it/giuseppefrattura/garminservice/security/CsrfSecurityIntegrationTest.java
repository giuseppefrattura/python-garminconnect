package it.giuseppefrattura.garminservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.giuseppefrattura.garminservice.model.User;
import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import it.giuseppefrattura.garminservice.service.TotpService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = CsrfSecurityIntegrationTest.TestConfig.class)
class CsrfSecurityIntegrationTest {
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(context.getBean(CustomUserDetailsService.class).findUser("alice"))
                .thenReturn(Optional.of(new User("alice", "hash", "ROLE_USER")));
    }

    @Test void sessionRequestsRequireTokenEvenWhenApiKeyIsPresent() throws Exception {
        mvc.perform(post("/login").param("username", "alice").param("password", "password"))
                .andExpect(status().isForbidden());
        var preLogin = mvc.perform(get("/login")).andReturn();
        Cookie loginCookie = preLogin.getResponse().getCookie("XSRF-TOKEN");
        var login = mvc.perform(post("/login").cookie(loginCookie)
                        .param("_csrf", loginCookie.getValue())
                        .param("username", "alice").param("password", "password"))
                .andExpect(status().isFound()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(post("/api/write").session(session).header("X-API-Key", "test-key"))
                .andExpect(status().isForbidden());
        var afterLogin = mvc.perform(get("/").session(session)).andReturn();
        Cookie cookie = afterLogin.getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/write").session(session).cookie(cookie)
                        .header("X-XSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/2fa/disable").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).cookie(cookie)
                        .header("X-XSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isFound());
    }

    @Test void onlyValidStatelessApiKeysCanWriteWithoutCsrf() throws Exception {
        mvc.perform(post("/api/write").header("X-API-Key", "test-key"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/write").header("X-API-Key", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/write")).andExpect(status().isForbidden());
    }

    @Test void oauthRoutesCannotAuthenticate() throws Exception {
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isFound());
        mvc.perform(post("/login/oauth2/code/apple").param("code", "untrusted-code").param("state", "untrusted-state"))
                .andExpect(status().isForbidden());
    }

    @Test void loginRequiresValidOtpWhenEnabled() throws Exception {
        var users = context.getBean(CustomUserDetailsService.class);
        var totp = context.getBean(TotpService.class);
        User user = new User("alice", "hash", "ROLE_USER");
        user.setTotpEnabled(true);
        user.setTotpSecret("secret");
        when(users.findUser("alice")).thenReturn(Optional.of(user));
        when(totp.verifyCode("secret", "123456", "SHA1")).thenReturn(true);
        var initial = mvc.perform(get("/login")).andReturn();
        Cookie cookie = initial.getResponse().getCookie("XSRF-TOKEN");
        var rejected = mvc.perform(post("/login").cookie(cookie).param("_csrf", cookie.getValue())
                        .param("username", "alice").param("password", "password"))
                .andExpect(status().isFound()).andReturn();
        assertTrue(rejected.getResponse().getRedirectedUrl().contains("error"));
        MockHttpSession rejectedSession = (MockHttpSession) rejected.getRequest().getSession(false);
        if (rejectedSession != null) {
            mvc.perform(post("/api/write").session(rejectedSession)).andExpect(status().isForbidden());
        }
        var next = mvc.perform(get("/login")).andReturn();
        Cookie newCookie = next.getResponse().getCookie("XSRF-TOKEN");
        var accepted = mvc.perform(post("/login").cookie(newCookie).param("_csrf", newCookie.getValue())
                        .param("username", "alice").param("password", "password").param("otp", "123456"))
                .andExpect(status().isFound()).andReturn();
        assertTrue("/".equals(accepted.getResponse().getRedirectedUrl()));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, TestController.class})
    static class TestConfig {
        @Bean ApiKeyFilter apiKeyFilter() { return new ApiKeyFilter("test-key", false); }
        @Bean RateLimitingFilter rateLimitingFilter() { return new RateLimitingFilter(""); }
        @Bean MetricsAuthFilter metricsAuthFilter() { return new MetricsAuthFilter("", "test-key", false); }
        @Bean TotpService totpService() { return mock(TotpService.class); }
        @Bean TotpAuthenticationProvider authenticationProvider(CustomUserDetailsService users, TotpService totp) {
            return new TotpAuthenticationProvider(users, totp,
                    org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder());
        }
        @Bean CustomUserDetailsService users() {
            CustomUserDetailsService users = mock(CustomUserDetailsService.class);
            when(users.loadUserByUsername("alice")).thenAnswer(invocation ->
                    org.springframework.security.core.userdetails.User.withUsername("alice")
                            .password("{noop}password").roles("USER").build());
            when(users.findUser("alice")).thenReturn(Optional.of(new User("alice", "hash", "ROLE_USER")));
            return users;
        }
    }

    @RestController
    static class TestController {
        @PostMapping({"/api/write", "/api/auth/2fa/disable"}) String write() { return "ok"; }
    }
}
