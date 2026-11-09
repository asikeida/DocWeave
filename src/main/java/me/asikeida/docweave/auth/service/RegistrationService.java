package me.asikeida.docweave.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.organization.dto.PersonalArea;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import me.asikeida.docweave.user.domain.UserAccount;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final UserAccountRepository users;
    private final PersonalAreaService areas;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Environment environment;
    private final Map<String, Attempt> attempts = new HashMap<>();
    private record Attempt(Instant start, int count) { }

    public RegistrationService(UserAccountRepository users, PersonalAreaService areas, PasswordEncoder passwords, Clock clock, Environment environment) {
        this.users=users; this.areas=areas; this.passwords=passwords; this.clock=clock; this.environment=environment;
    }

    /** 保持默认关闭；生产不能仅靠打开开发开关暴露公网注册。 */
    public synchronized void checkAdmission(String address) {
        if (!environment.acceptsProfiles(Profiles.of("local | test")) || environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.getProperty("docweave.registration.enabled", Boolean.class, false)) {
            throw new AuthFailure("REGISTRATION_DISABLED", "当前环境未开放注册", 403);
        }
        Instant now=clock.instant();
        attempts.entrySet().removeIf(entry -> !entry.getValue().start().plus(Duration.ofHours(1)).isAfter(now));
        Attempt previous=attempts.get(address);
        if ((previous != null && previous.count() >= 5) || (previous == null && attempts.size() >= 4096)) {
            throw new AuthFailure("REGISTRATION_RATE_LIMITED", "注册尝试过于频繁，请稍后重试", 429);
        }
        attempts.put(address, previous == null ? new Attempt(now, 1) : new Attempt(previous.start(), previous.count()+1));
    }

    @Transactional
    public PersonalArea register(RegistrationRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AuthFailure("INVALID_PASSWORD", "密码UTF-8编码不能超过72字节", 400);
        }
        String username=request.username().toLowerCase(Locale.ROOT);
        if (users.findByUsername(username).isPresent()) { throw unavailable(); }
        UserAccount user;
        try {
            user=users.saveAndFlush(new UserAccount(username, passwords.encode(request.password()), request.displayName().trim(), clock.instant()));
        } catch (org.springframework.dao.DataIntegrityViolationException failure) {
            // 仅账号插入的冲突转成409；后续聚合写入错误应回滚并作为系统失败处理。
            throw unavailable();
        }
        return areas.initialize(user.getId());
    }

    public static AuthFailure unavailable() { return new AuthFailure("ACCOUNT_UNAVAILABLE", "账号不可用，请更换账号或返回登录", 409); }
}
