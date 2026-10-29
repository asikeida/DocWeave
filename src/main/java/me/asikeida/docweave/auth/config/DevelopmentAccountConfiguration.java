package me.asikeida.docweave.auth.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import me.asikeida.docweave.user.domain.UserAccount;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅显式启用的 local 开发账号初始化；不开放注册接口，不覆盖现有密码。 */
@Configuration
@Profile("local & !prod")
@ConditionalOnProperty(name = "docweave.bootstrap.enabled", havingValue = "true")
public class DevelopmentAccountConfiguration {
    @Bean
    ApplicationRunner createDevelopmentAccount(UserAccountRepository users, PasswordEncoder passwords,
            Environment environment, Clock clock, TransactionTemplate transaction,
            ConfigurableApplicationContext context) {
        return args -> {
            String username = environment.getProperty("DOCWEAVE_BOOTSTRAP_USERNAME", "").toLowerCase(Locale.ROOT);
            String password = environment.getProperty("DOCWEAVE_BOOTSTRAP_PASSWORD", "");
            if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}")
                    || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("初始化需有效账号及至少 12 字符、不超过 72 字节的密码");
            }
            transaction.executeWithoutResult(status -> {
                if (users.findByUsername(username).isEmpty()) {
                    users.save(new UserAccount(username, passwords.encode(password), username, clock.instant()));
                }
            });
            LoggerFactory.getLogger(getClass()).info("Development account initialization completed; existing credentials unchanged");
            if ("none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type"))) {
                context.close();
            }
        };
    }
}
