package me.asikeida.docweave.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.HexFormat;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.document.config.DocumentProperties;
import me.asikeida.docweave.document.domain.DocumentFailure;
import me.asikeida.docweave.ingestion.config.ProcessingProperties;
import me.asikeida.docweave.ingestion.parser.ParsedDocument;
import me.asikeida.docweave.ingestion.repository.ProcessingRepository;
import me.asikeida.docweave.ingestion.repository.ProcessingRepository.*;
import me.asikeida.docweave.organization.repository.PersonalAreaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务调度和发布；账号→文档→任务锁顺序与上传/删除一致，解析不占数据库事务。 */
@Service
public class ProcessingService {
    private final ProcessingRepository repository;
    private final PersonalAreaRepository areas;
    private final ProcessingProperties properties;
    private final DocumentProperties documents;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProcessingService(ProcessingRepository repository, PersonalAreaRepository areas, ProcessingProperties properties,
                             DocumentProperties documents, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository; this.areas = areas; this.properties = properties; this.documents = documents; this.clock = clock;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 可参加上传确认事务；已有任务原样返回，重复请求不重置失败或尝试次数。 */
    public Job enqueue(String user, String document) {
        if (!documents.writeEnabled()) throw new DocumentFailure("DOCUMENT_WRITES_DISABLED", "当前环境暂未开放文件写入", 403);
        return tx.execute(s -> {
            repository.lockUser(user);
            var area = area(user);
            repository.lockDocument(area.organizationId(), document);
            Target target = authorized(user, document);
            if (!"UPLOAD".equals(target.source()) || target.key() == null) throw new DocumentFailure("PROCESSING_NOT_SUPPORTED", "该文档没有待解析原文件", 409);
            var existing = repository.jobFor(target.tenant(), target.version());
            if (existing.isPresent()) return existing.get();
            if (repository.pending(user) >= properties.maxPendingPerUser()) throw new DocumentFailure("PROCESSING_QUEUE_FULL", "待处理文档数量已达上限，请稍后重试", 429);
            repository.insert(target, clock.instant());
            return repository.jobFor(target.tenant(), target.version()).orElseThrow();
        });
    }

    /** 不公开 claim token；后续 HTTP 层应使用独立状态 DTO。当前供后台及集成审核读取。 */
    public String state(String user, String document) {
        return tx.execute(s -> {
            repository.lockUser(user);
            Target target = authorized(user, document);
            return "SAMPLE".equals(target.source()) ? "SUCCEEDED"
                    : repository.jobFor(target.tenant(), target.version()).map(Job::status).orElse("NOT_REQUESTED");
        });
    }

    public Claim claimNext() {
        for (Job candidate : repository.candidates(clock.instant())) {
            Claim result = tx.execute(s -> {
                lock(candidate);
                Job current = repository.lockJob(candidate.tenant(), candidate.id()).orElse(null);
                Instant now = clock.instant();
                if (current == null) return null;
                if (current.status().equals("RUNNING")) {
                    if (current.lease() != null && !current.lease().isAfter(now)) {
                        failLocked(current, "PROCESSING_LEASE_EXPIRED", true, now);
                    }
                    return null;
                }
                if (!current.status().equals("PENDING") || current.nextAttempt() == null || current.nextAttempt().isAfter(now)) return null;
                Target target = available(current);
                if (target == null) { repository.finish(current, "CANCELLED", "RESOURCE_UNAVAILABLE", now, null); return null; }
                if (current.attempts() >= properties.maxAttempts()) { repository.finish(current, "FAILED", "ATTEMPTS_EXHAUSTED", now, null); return null; }
                repository.claim(current, java.util.UUID.randomUUID().toString(), now, now.plus(properties.lease()));
                Job claimed = repository.lockJob(current.tenant(), current.id()).orElseThrow();
                return new Claim(claimed, target);
            });
            if (result != null) return result;
        }
        return null;
    }

    public boolean renew(Claim claim) {
        return tx.execute(s -> {
            lock(claim.job());
            Job current = repository.lockJob(claim.job().tenant(), claim.job().id()).orElse(null);
            Instant now = clock.instant();
            if (current == null || !repository.fenced(claim.job(), current, now)) return false;
            if (available(current) == null) { repository.finish(current, "CANCELLED", "RESOURCE_UNAVAILABLE", now, null); return false; }
            return repository.renew(claim.job(), now, now.plus(properties.lease()));
        });
    }

    /** 返回 false 表示结果已失去发布资格；不能用旧 token 覆盖后来的任务。 */
    public boolean publish(Claim claim, ParsedDocument parsed) {
        if (parsed == null || parsed.text() == null || parsed.text().isBlank()
                || parsed.text().getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid parsed document");
        return tx.execute(s -> {
            lock(claim.job());
            Job current = repository.lockJob(claim.job().tenant(), claim.job().id()).orElse(null);
            Instant now = clock.instant();
            if (current == null || !repository.fenced(claim.job(), current, now)) return false;
            if (available(current) == null) { repository.finish(current, "CANCELLED", "RESOURCE_UNAVAILABLE", now, null); return false; }
            repository.publish(current, parsed, digest(parsed.text()), now);
            return true;
        });
    }

    public void failed(Claim claim, String error, boolean retryable) {
        tx.executeWithoutResult(s -> {
            lock(claim.job());
            Job current = repository.lockJob(claim.job().tenant(), claim.job().id()).orElse(null);
            Instant now = clock.instant();
            if (current == null || !repository.fenced(claim.job(), current, now)) return;
            if (available(current) == null) repository.finish(current, "CANCELLED", "RESOURCE_UNAVAILABLE", now, null);
            else failLocked(current, error, retryable, now);
        });
    }

    /** 在现有删除事务中调用；即使没有解析任务也先锁文档，和结果发布串行化。 */
    public void cancelForDeletion(String tenant, String document) {
        repository.lockDocument(tenant, document);
        repository.cancel(tenant, document, clock.instant());
    }

    private void failLocked(Job job, String error, boolean retryable, Instant now) {
        boolean retry = retryable && job.attempts() < properties.maxAttempts();
        long seconds = switch (job.attempts()) { case 0, 1 -> 30; case 2 -> 120; case 3 -> 600; default -> 1800; };
        repository.finish(job, retry ? "PENDING" : "FAILED", error, now, retry ? now.plusSeconds(seconds) : null);
    }
    private void lock(Job job) {
        repository.lockUser(job.user());
        repository.lockDocument(job.tenant(), job.document());
    }
    private Target available(Job job) {
        try {
            Target target = authorized(job.user(), job.document());
            return target.tenant().equals(job.tenant()) && target.version().equals(job.version())
                    && "UPLOAD".equals(target.source()) && target.key() != null ? target : null;
        } catch (AuthFailure | DocumentFailure e) { return null; }
    }
    private Target authorized(String user, String doc) {
        if (!repository.activeUser(user)) throw AuthFailure.invalidLogin();
        var area = area(user);
        repository.lockAuthorization(area.organizationId(), area.workspaceId(), user);
        // 锁后重新读取，确保撤权在锁等待期间完成时不会使用旧的允许结果。
        area = area(user);
        repository.lockContainer(area.organizationId(), doc);
        return repository.target(area.organizationId(), area.workspaceId(), doc)
                .orElseThrow(() -> new DocumentFailure("RESOURCE_NOT_FOUND", "资源不存在或不可访问", 404));
    }
    private me.asikeida.docweave.organization.dto.PersonalArea area(String user) {
        return areas.findAuthorized(user).orElseThrow(() -> new AuthFailure("PERSONAL_AREA_UNAVAILABLE", "个人区域尚未初始化或暂不可用", 404));
    }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
