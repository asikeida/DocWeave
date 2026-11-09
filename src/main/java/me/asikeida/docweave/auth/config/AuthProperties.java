package me.asikeida.docweave.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 从 YAML 绑定认证配置；Duration 支持 5m、7d，密钥为至少 32 随机字节的 Base64，禁止输出。 */
@ConfigurationProperties("docweave.auth")
public record AuthProperties(
        Duration accessTtl,
        Duration loginTtl,
        boolean cookieSecure,
        String issuer,
        String audience,
        int loginAttemptsPerMinute,
        String jwtSecret
) {
    public AuthProperties {
        if (accessTtl == null || loginTtl == null || issuer == null || audience == null
                || accessTtl.isNegative() || accessTtl.isZero() || accessTtl.compareTo(Duration.ofMinutes(15)) > 0
                || loginTtl.compareTo(accessTtl) < 0 || loginTtl.compareTo(Duration.ofDays(30)) > 0
                || loginAttemptsPerMinute < 1 || issuer.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("认证有效期或配置范围不正确");
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[accessTtl=" + accessTtl + ", loginTtl=" + loginTtl
                + ", cookieSecure=" + cookieSecure + ", issuer=" + issuer + ", audience=" + audience
                + ", loginAttemptsPerMinute=" + loginAttemptsPerMinute + ", jwtSecret=[REDACTED]]";
    }
}
