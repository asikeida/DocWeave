package me.asikeida.docweave.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 登录有效期和浏览器安全设置；生产 Cookie 必须使用 HTTPS。 */
@ConfigurationProperties("docweave.auth")
public record AuthProperties(
        @DefaultValue("5m") Duration accessTtl,
        @DefaultValue("7d") Duration loginTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("docweave") String issuer,
        @DefaultValue("docweave-api") String audience,
        @DefaultValue("20") int loginAttemptsPerMinute
) {
    public AuthProperties {
        if (accessTtl.isNegative() || accessTtl.isZero() || accessTtl.compareTo(Duration.ofMinutes(15)) > 0
                || loginTtl.compareTo(accessTtl) < 0 || loginTtl.compareTo(Duration.ofDays(30)) > 0
                || loginAttemptsPerMinute < 1 || issuer.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("认证有效期或配置范围不正确");
        }
    }
}
