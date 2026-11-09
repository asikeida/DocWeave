package me.asikeida.docweave.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import me.asikeida.docweave.auth.config.AuthProperties;
import me.asikeida.docweave.auth.config.TokenConfiguration;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.auth.service.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthenticationPolicyTests {
    @Test
    void loginAttemptsAreLimitedWithoutTrustingUserSuppliedIdentity() {
        AuthProperties properties = properties(false);
        var limiter = new LoginAttemptLimiter(Clock.fixed(Instant.now(), ZoneOffset.UTC), properties);
        limiter.check("client-address");
        limiter.check("client-address");
        assertThatThrownBy(() -> limiter.check("client-address")).isInstanceOfSatisfying(AuthFailure.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(429));
        limiter.check("other-client");
    }

    @Test
    void productionNeverLoadsDevelopmentBootstrapEvenIfLocalIsAlsoActive() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local,prod", "docweave.bootstrap.enabled=true")
                .withUserConfiguration(DevelopmentAccountConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.containsBean("createDevelopmentAccount")).isFalse();
                });
    }

    @Test
    void productionRequiresExplicitSecretAndSecureCookies() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var configuration = new TokenConfiguration();
        assertThatThrownBy(() -> configuration.jwtSigningKey(production, properties(true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DOCWEAVE_JWT_SECRET");
        assertThatThrownBy(() -> configuration.jwtSigningKey(production, properties(false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cookie");
    }

    private AuthProperties properties(boolean secure) {
        return new AuthProperties(Duration.ofMinutes(5), Duration.ofDays(7), secure, "docweave", "docweave-api", 2);
    }
}
