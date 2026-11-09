package me.asikeida.docweave.ingestion.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 解析调度配置；时间使用 Duration，资源上限校验避免误配置破坏租约和进程限制。 */
@ConfigurationProperties("docweave.processing")
public record ProcessingProperties(boolean workerEnabled, Path root, Duration timeout, Duration lease,
                                   Duration heartbeat, int maxPendingPerUser, int maxAttempts) {
    public ProcessingProperties {
        if (root == null || timeout == null || lease == null || heartbeat == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0
                || lease.compareTo(timeout.plusSeconds(15)) < 0 || lease.compareTo(Duration.ofMinutes(5)) > 0
                || heartbeat.compareTo(Duration.ofSeconds(1)) < 0 || heartbeat.compareTo(lease.dividedBy(3)) > 0
                || maxPendingPerUser < 1 || maxPendingPerUser > 100 || maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("Invalid processing resource or lease limits");
        }
    }
}
