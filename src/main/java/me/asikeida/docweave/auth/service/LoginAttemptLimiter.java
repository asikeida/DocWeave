package me.asikeida.docweave.auth.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import me.asikeida.docweave.auth.config.AuthProperties;
import org.springframework.stereotype.Component;

/** 单实例的登录限速；不信任客户端提交的 X-Forwarded-For，生产多实例另配网关策略。 */
@Component
public class LoginAttemptLimiter {
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final Clock clock;
    private final int limit;

    public LoginAttemptLimiter(Clock clock, AuthProperties properties) {
        this.clock = clock;
        this.limit = properties.loginAttemptsPerMinute();
    }

    public synchronized void check(String address) {
        long minute = clock.instant().getEpochSecond() / 60;
        Window window = windows.get(address);
        int count = window != null && window.minute() == minute ? window.count() + 1 : 1;
        windows.put(address, new Window(minute, count));
        if (windows.size() > 4096) { windows.remove(windows.keySet().iterator().next()); }
        if (count > limit) {
            throw new AuthFailure("TOO_MANY_ATTEMPTS", "登录尝试过于频繁，请稍后重试", 429);
        }
    }

    private record Window(long minute, int count) { }
}
