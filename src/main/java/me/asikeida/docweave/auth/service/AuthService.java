package me.asikeida.docweave.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.asikeida.docweave.auth.config.AuthProperties;
import me.asikeida.docweave.auth.domain.LoginSession;
import me.asikeida.docweave.auth.domain.RefreshCredential;
import me.asikeida.docweave.auth.dto.CurrentUser;
import me.asikeida.docweave.auth.dto.TokenResponse;
import me.asikeida.docweave.auth.repository.LoginSessionRepository;
import me.asikeida.docweave.auth.repository.RefreshCredentialRepository;
import me.asikeida.docweave.user.domain.UserAccount;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserAccountRepository users;
    private final LoginSessionRepository sessions;
    private final RefreshCredentialRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserAccountRepository users, LoginSessionRepository sessions,
            RefreshCredentialRepository refreshTokens, PasswordEncoder passwords, JwtEncoder encoder,
            AuthProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public IssuedLogin login(String username, String password, String previousRefresh) {
        UserAccount account = users.findByUsername(username.toLowerCase(Locale.ROOT)).orElse(null);
        boolean valid = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, account == null ? dummyPasswordHash : account.getPasswordHash());
        if (!valid || account == null || !account.isActive()) {
            log.warn("Authentication rejected: invalid credentials or inactive account");
            throw AuthFailure.invalidCredentials();
        }
        revokeByRefresh(previousRefresh);
        Instant now = clock.instant();
        LoginSession session = sessions.save(new LoginSession(account.getId(), now, now.plus(properties.loginTtl())));
        log.info("Authentication succeeded: userId={}", account.getId());
        return issue(account, session);
    }

    /** 旧刷新凭据重放时撤销整次登录；认证失败不能回滚撤销记录。 */
    @Transactional(noRollbackFor = AuthFailure.class)
    public IssuedLogin refresh(String rawToken) {
        RefreshCredential credential = refreshTokens.lockByHash(hashChecked(rawToken)).orElseThrow(AuthFailure::invalidLogin);
        LoginSession session = sessions.lockById(credential.getSessionId()).orElseThrow(AuthFailure::invalidLogin);
        if (credential.isConsumed()) {
            session.revoke();
            log.warn("Refresh replay detected; login revoked");
            throw AuthFailure.invalidLogin();
        }
        UserAccount account = users.findById(session.getUserId()).orElseThrow(AuthFailure::invalidLogin);
        if (!session.isActive(clock.instant()) || !account.isActive()) {
            session.revoke();
            throw AuthFailure.invalidLogin();
        }
        credential.consume();
        return issue(account, session);
    }

    @Transactional
    public void logout(String rawToken, Jwt jwt) {
        revokeByRefresh(rawToken);
        if (jwt != null) {
            sessions.lockById(jwt.getClaimAsString("sid")).ifPresent(LoginSession::revoke);
        }
        log.info("Logout completed");
    }

    /** JWT 签名有效仍不代表登录有效；每次受保护请求核验撤销和账号状态。 */
    @Transactional(readOnly = true)
    public CurrentUser currentUser(Jwt jwt) {
        LoginSession session = sessions.findById(jwt.getClaimAsString("sid")).orElseThrow(AuthFailure::invalidLogin);
        if (!session.getUserId().equals(jwt.getSubject()) || !session.isActive(clock.instant())) {
            throw AuthFailure.invalidLogin();
        }
        UserAccount account = users.findById(session.getUserId()).orElseThrow(AuthFailure::invalidLogin);
        if (!account.isActive()) { throw AuthFailure.invalidLogin(); }
        return CurrentUser.from(account);
    }

    private IssuedLogin issue(UserAccount account, LoginSession session) {
        Instant now = clock.instant();
        Instant expires = now.plus(properties.accessTtl());
        if (expires.isAfter(session.getExpiresAt())) { expires = session.getExpiresAt(); }
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer())
                .audience(List.of(properties.audience())).subject(account.getId())
                .issuedAt(now).notBefore(now).expiresAt(expires).id(UUID.randomUUID().toString())
                .claim("sid", session.getId()).claim("token_use", "access").build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshCredential(hashChecked(refresh), session.getId()));
        return new IssuedLogin(new TokenResponse(access, "Bearer", Duration.between(now, expires).toSeconds(),
                CurrentUser.from(account)), refresh, session.getExpiresAt());
    }

    private void revokeByRefresh(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) { return; }
        refreshTokens.lockByHash(hashChecked(rawToken)).ifPresent(credential ->
                sessions.lockById(credential.getSessionId()).ifPresent(LoginSession::revoke));
    }

    private String hashChecked(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw AuthFailure.invalidLogin(); }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record IssuedLogin(TokenResponse response, String refreshToken, Instant refreshExpiresAt) {
        @Override
        public String toString() { return "IssuedLogin[credentials=REDACTED]"; }
    }
}
