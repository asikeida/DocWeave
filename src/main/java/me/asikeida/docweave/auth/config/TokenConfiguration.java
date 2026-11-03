package me.asikeida.docweave.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class TokenConfiguration {
    @Bean
    Clock authClock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", java.util.Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    SecretKey jwtSigningKey(Environment environment, AuthProperties properties) throws IOException {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && !properties.cookieSecure()) {
            throw new IllegalStateException("生产环境不能关闭安全 Cookie");
        }
        String configured = properties.jwtSecret();
        byte[] key;
        if (configured != null && !configured.isBlank()) {
            try {
                key = Base64.getDecoder().decode(configured);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("DOCWEAVE_JWT_SECRET 必须是 Base64 编码的随机密钥");
            }
        } else if (production) {
            throw new IllegalStateException("生产环境必须配置 DOCWEAVE_JWT_SECRET");
        } else if (environment.acceptsProfiles(Profiles.of("test"))) {
            key = randomKey();
        } else {
            // 开发密钥保存在被 Git 忽略的位置，重启后可继续验证现有登录。
            Path file = Path.of(".env.auth-signing-key");
            if (!Files.exists(file)) {
                try {
                    Files.writeString(file, Base64.getEncoder().encodeToString(randomKey()), StandardOpenOption.CREATE_NEW);
                    try {
                        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                    } catch (UnsupportedOperationException ignored) {
                        // Windows 使用当前用户目录的访问控制，不修改全局 ACL。
                    }
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // 并发启动时复用另一进程刚生成的密钥。
                }
            }
            key = Base64.getDecoder().decode(Files.readString(file).trim());
        }
        if (key.length < 32) { throw new IllegalStateException("JWT 密钥至少需要 256 位随机数据"); }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, AuthProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && jwt.getSubject() != null && jwt.getSubject().matches("[a-f0-9-]{36}")
                    && jwt.getClaimAsString("sid") != null && jwt.getClaimAsString("sid").matches("[a-f0-9-]{36}")
                    && "access".equals(jwt.getClaimAsString("token_use"))
                    && jwt.getAudience() != null && jwt.getAudience().contains(properties.audience());
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "令牌不可用", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()), claims));
        return decoder;
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
