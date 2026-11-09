package me.asikeida.docweave.auth.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import me.asikeida.docweave.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String REFRESH_COOKIE = "docweave_refresh";
    private final AuthProperties properties;
    private final Clock clock;

    public AuthCookies(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String refresh(String token, Instant expiresAt) {
        return cookie(token, Duration.between(clock.instant(), expiresAt));
    }

    public String clear() { return cookie("", Duration.ZERO); }

    private String cookie(String value, Duration age) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true)
                .secure(properties.cookieSecure()).sameSite("Strict").path("/api/v1/auth")
                .maxAge(age.isNegative() ? Duration.ZERO : age).build().toString();
    }
}
