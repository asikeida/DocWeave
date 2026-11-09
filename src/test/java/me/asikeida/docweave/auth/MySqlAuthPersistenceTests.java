package me.asikeida.docweave.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.auth.service.AuthService;
import me.asikeida.docweave.user.domain.UserAccount;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** 仅连接显式提供的隔离测试库；CI 配置临时 MySQL，本机绝不默认连接共享开发库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=${DOCWEAVE_TEST_MYSQL_URL}",
        "spring.datasource.username=${DOCWEAVE_TEST_MYSQL_USERNAME}",
        "spring.datasource.password=${DOCWEAVE_TEST_MYSQL_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DOCWEAVE_TEST_MYSQL_URL", matches = ".+")
class MySqlAuthPersistenceTests {
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @Autowired AuthService auth;
    @Autowired JwtDecoder decoder;
    @Autowired Flyway flyway;

    @Test
    void realMigrationEnforcesUniquenessAndDoesNotRepeat() {
        assertThat(flyway.info().applied()).hasSize(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        String username = "mysql-" + UUID.randomUUID().toString().substring(0, 8);
        String hash = passwords.encode(UUID.randomUUID().toString());
        users.saveAndFlush(new UserAccount(username, hash, "MySQL 验证", clock.instant()));
        assertThatThrownBy(() -> users.saveAndFlush(new UserAccount(username, hash, "重复账号", clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refreshReplayCommitsRevocationOnRealMysql() {
        String username = "mysql-" + UUID.randomUUID().toString().substring(0, 8);
        String password = UUID.randomUUID().toString();
        users.saveAndFlush(new UserAccount(username, passwords.encode(password), "MySQL 验证", clock.instant()));
        var first = auth.login(username, password, null);
        var second = auth.refresh(first.refreshToken());
        assertThat(auth.currentUser(decoder.decode(second.response().accessToken())).username()).isEqualTo(username);
        assertThatThrownBy(() -> auth.refresh(first.refreshToken())).isInstanceOf(AuthFailure.class);
        assertThatThrownBy(() -> auth.currentUser(decoder.decode(second.response().accessToken())))
                .isInstanceOf(AuthFailure.class);
    }
}
