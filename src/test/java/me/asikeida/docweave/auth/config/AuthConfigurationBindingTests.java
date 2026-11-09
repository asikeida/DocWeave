package me.asikeida.docweave.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AuthConfigurationBindingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=test")
            .withUserConfiguration(TokenConfiguration.class);

    @Test
    void readsDefaultsFromYamlAndAppliesProfileOverrides() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AuthProperties.class);
            assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.loginTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.issuer()).isEqualTo("docweave");
            assertThat(properties.audience()).isEqualTo("docweave-api");
            assertThat(properties.cookieSecure()).isFalse();
            assertThat(properties.loginAttemptsPerMinute()).isEqualTo(1000);
        });
    }

    @Test
    void bindsOverriddenSettingsAndLegacySecretPlaceholderWithoutPrintingSecret() {
        // 仅测试夹具，不可作为部署密钥。
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        runner.withPropertyValues("docweave.auth.access-ttl=10m", "docweave.auth.login-ttl=14d",
                "docweave.auth.issuer=test-issuer", "docweave.auth.audience=test-api",
                "DOCWEAVE_JWT_SECRET=" + secret).run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AuthProperties.class);
                    assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.loginTtl()).isEqualTo(Duration.ofDays(14));
                    assertThat(properties.issuer()).isEqualTo("test-issuer");
                    assertThat(properties.audience()).isEqualTo("test-api");
                    assertThat(context.getBean(SecretKey.class).getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
                    assertThat(properties.toString()).doesNotContain(secret).contains("REDACTED");
                });
    }

    @Test
    void rejectsUnsafeLifetimeAndInvalidSigningKeys() {
        runner.withPropertyValues("docweave.auth.access-ttl=16m")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("docweave.auth.jwt-secret=invalid-base64!")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("docweave.auth.jwt-secret=c2hvcnQ=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionYamlRequiresSecretAndSecureCookie() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
        String secret = Base64.getEncoder().encodeToString(new byte[32]);
        runner.withPropertyValues("spring.profiles.active=prod", "DOCWEAVE_JWT_SECRET=" + secret)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuthProperties.class).cookieSecure()).isTrue();
                });
        runner.withPropertyValues("spring.profiles.active=prod", "DOCWEAVE_JWT_SECRET=" + secret,
                "docweave.auth.cookie-secure=false")
                .run(context -> assertThat(context).hasFailed());
    }
}
