package me.asikeida.docweave.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import me.asikeida.docweave.auth.config.AuthProperties;
import me.asikeida.docweave.auth.web.AuthCookies;
import me.asikeida.docweave.user.domain.UserAccount;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AuthFlowTests.DeniedProbe.class)
class AuthFlowTests {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AuthProperties properties;
    @Autowired Clock clock;
    private UserAccount user;
    private String password;

    @BeforeEach
    void createAccount() {
        password = UUID.randomUUID() + "-test";
        user = users.saveAndFlush(new UserAccount("test-" + UUID.randomUUID().toString().substring(0, 8),
                passwords.encode(password), "测试成员", clock.instant()));
    }

    @Test
    void loginRequiresCsrfAndProtectedRequestsHaveRequestId() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", user.getUsername(), "password", password))))
                .andExpect(status().isForbidden()).andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id")).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void encodedAuthPathsCannotBypassCsrfProtection() throws Exception {
        mvc.perform(post(java.net.URI.create("/api/v1/%61uth/refresh")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void loginReturnsSafeUserAndHttpOnlyRefreshCookie() throws Exception {
        MvcResult result = signIn(user.getUsername(), password);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(password, "passwordHash", "refreshToken");
        Cookie cookie = refreshCookie(result);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SameSite=Strict");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(result)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value(user.getUsername()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM auth_refresh_tokens WHERE session_id = ?",
                String.class, sessionId(access(result)));
        assertThat(storedHash).hasSize(64).isNotEqualTo(cookie.getValue());
    }

    @Test
    void wrongPasswordUnknownAndDisabledAccountsUseSameError() throws Exception {
        assertInvalidCredentials(signIn(user.getUsername(), "incorrect-test-password"));
        assertInvalidCredentials(signIn("unknown-account", "incorrect-test-password"));
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user.getId());
        assertInvalidCredentials(signIn(user.getUsername(), password));
    }

    @Test
    void canonicalUsernameWorksAndInvalidBodyIs400() throws Exception {
        assertThat(signIn(user.getUsername().toUpperCase(java.util.Locale.ROOT), password).getResponse().getStatus()).isEqualTo(200);
        MvcResult invalid = mutate("/api/v1/auth/login", "{}", null);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(json.readTree(invalid.getResponse().getContentAsString()).get("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void refreshRotatesAndReplayRevokesEvenTheNewAccessToken() throws Exception {
        MvcResult first = signIn(user.getUsername(), password);
        Cookie old = refreshCookie(first);
        MvcResult second = mutate("/api/v1/auth/refresh", "{}", old);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(refreshCookie(second).getValue()).isNotEqualTo(old.getValue());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(second))).andExpect(status().isOk());
        MvcResult replay = mutate("/api/v1/auth/refresh", "{}", old);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(refreshCookie(replay).getMaxAge()).isZero();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(second)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutImmediatelyInvalidatesAccessAndRefreshAndIsIdempotent() throws Exception {
        MvcResult signedIn = signIn(user.getUsername(), password);
        assertThat(mutate("/api/v1/auth/logout", "{}", refreshCookie(signedIn)).getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(signedIn)))
                .andExpect(status().isUnauthorized());
        assertThat(mutate("/api/v1/auth/refresh", "{}", refreshCookie(signedIn)).getResponse().getStatus()).isEqualTo(401);
        assertThat(mutate("/api/v1/auth/logout", "{}", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void disabledOrExpiredLoginCannotUseValidSignedJwt() throws Exception {
        MvcResult signedIn = signIn(user.getUsername(), password);
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user.getId());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(signedIn))).andExpect(status().isUnauthorized());
        assertThat(mutate("/api/v1/auth/refresh", "{}", refreshCookie(signedIn)).getResponse().getStatus()).isEqualTo(401);
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user.getId());
        MvcResult another = signIn(user.getUsername(), password);
        jdbc.update("UPDATE auth_sessions SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), sessionId(access(another)));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(another))).andExpect(status().isUnauthorized());
    }

    @Test
    void signedButExpiredWrongAudienceOrWrongPurposeTokensAreRejected() throws Exception {
        MvcResult signedIn = signIn(user.getUsername(), password);
        String sid = sessionId(access(signedIn));
        for (String token : List.of(jwt(sid, "docweave-api", "access", clock.instant().minusSeconds(1)),
                jwt(sid, "other-api", "access", clock.instant().plusSeconds(60)),
                jwt(sid, null, "access", clock.instant().plusSeconds(60)),
                jwt(sid, "docweave-api", "refresh", clock.instant().plusSeconds(60)),
                jwt(UUID.randomUUID().toString(), "docweave-api", "access", clock.instant().plusSeconds(60)),
                "not.a.valid-token")) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void refreshCannotBeSubmittedWithoutCsrfOrInJsonBody() throws Exception {
        MvcResult signedIn = signIn(user.getUsername(), password);
        mvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie(signedIn)))
                .andExpect(status().isForbidden());
        MvcResult bodyOnly = mutate("/api/v1/auth/refresh",
                json.writeValueAsString(java.util.Map.of("refreshToken", refreshCookie(signedIn).getValue())), null);
        assertThat(bodyOnly.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void concurrentRefreshDoesNotAllowReusingOldCredential() throws Exception {
        Cookie cookie = refreshCookie(signIn(user.getUsername(), password));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> request = () -> mutate("/api/v1/auth/refresh", "{}", cookie);
            var results = executor.invokeAll(List.of(request, request));
            MvcResult first = results.get(0).get();
            MvcResult second = results.get(1).get();
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);
            MvcResult success = first.getResponse().getStatus() == 200 ? first : second;
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(success)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void replacingBrowserLoginRevokesPreviousSessionAndControllerDenialIs403() throws Exception {
        MvcResult first = signIn(user.getUsername(), password);
        MvcResult second = mutate("/api/v1/auth/login", json.writeValueAsString(
                java.util.Map.of("username", user.getUsername(), "password", password)), refreshCookie(first));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(first))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/security-probe").header("Authorization", "Bearer " + access(second)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(header().exists("X-Request-Id"));
    }

    private MvcResult signIn(String username, String rawPassword) throws Exception {
        return mutate("/api/v1/auth/login", json.writeValueAsString(java.util.Map.of("username", username, "password", rawPassword)), null);
    }

    private MvcResult mutate(String path, String body, Cookie refresh) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var payload = json.readTree(csrf.getResponse().getContentAsString());
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                .cookie(csrf.getResponse().getCookie("docweave_csrf"))
                .header(payload.get("headerName").asString(), payload.get("token").asString());
        if (refresh != null) { request.cookie(refresh); }
        return mvc.perform(request).andReturn();
    }

    private Cookie refreshCookie(MvcResult result) {
        return result.getResponse().getCookie(AuthCookies.REFRESH_COOKIE);
    }

    private String access(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();
    }

    private String sessionId(String access) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(payload).get("sid").asString();
    }

    private void assertInvalidCredentials(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    private String jwt(String sid, String audience, String purpose, Instant expiry) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(properties.issuer()).subject(user.getId())
                .issuedAt(clock.instant().minusSeconds(120)).expiresAt(expiry).claim("sid", sid).claim("token_use", purpose);
        if (audience != null) { builder.audience(List.of(audience)); }
        JwtClaimsSet claims = builder.build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @RestController
    static class DeniedProbe {
        @GetMapping("/api/v1/security-probe")
        void denied() { throw new AccessDeniedException("test denial"); }
    }
}
