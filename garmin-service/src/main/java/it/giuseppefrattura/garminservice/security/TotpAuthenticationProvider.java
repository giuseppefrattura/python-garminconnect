package it.giuseppefrattura.garminservice.security;

import it.giuseppefrattura.garminservice.service.CustomUserDetailsService;
import it.giuseppefrattura.garminservice.service.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/** Checks the second factor before Spring creates an authenticated session. */
@Component
public class TotpAuthenticationProvider extends DaoAuthenticationProvider {
    private final CustomUserDetailsService users;
    private final TotpService totp;

    public TotpAuthenticationProvider(CustomUserDetailsService users, TotpService totp, PasswordEncoder encoder) {
        this.users = users;
        this.totp = totp;
        setUserDetailsService(users);
        setPasswordEncoder(encoder);
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails details, UsernamePasswordAuthenticationToken authentication) {
        super.additionalAuthenticationChecks(details, authentication);
        var user = users.findUser(details.getUsername()).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (user.isTotpEnabled()) {
            Object input = authentication.getDetails();
            String code = input instanceof Details d ? d.code : null;
            if (!totp.verifyCode(user.getTotpSecret(), code, user.getTotpAlgorithm())) {
                throw new BadCredentialsException("Invalid credentials");
            }
        }
    }

    static class Details extends WebAuthenticationDetails {
        final String code;
        Details(HttpServletRequest request) {
            super(request);
            code = request.getParameter("otp");
        }
    }
}
