package me.asikeida.docweave.document.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 文件限制均为字节/次数；开发和生产目录必须不同，写入默认关闭。 */
@ConfigurationProperties("docweave.documents")
public record DocumentProperties(Path root, boolean writeEnabled, long maxFileBytes, long maxUserBytes,
        int maxConcurrent, int dailyLimit, Duration uploadLease) {
    public DocumentProperties {
        if (root == null || maxFileBytes < 1 || maxUserBytes < maxFileBytes || maxConcurrent < 1
                || dailyLimit < 1 || uploadLease == null || uploadLease.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("文件存储配置范围不正确");
        }
    }
}
