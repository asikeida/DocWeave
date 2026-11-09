package me.asikeida.docweave.ingestion.service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asikeida.docweave.document.storage.FileStorage;
import me.asikeida.docweave.ingestion.config.ProcessingProperties;
import me.asikeida.docweave.ingestion.parser.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 调度线程只投递一个任务；独立心跳线程续租，避免解析阻塞其他应用定时任务。 */
@Component
@EnableScheduling
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingWorker.class);
    private static final Set<ParsingFailure.Code> TRANSIENT = Set.of(ParsingFailure.Code.PARSER_UNAVAILABLE,
            ParsingFailure.Code.PARSER_BUSY, ParsingFailure.Code.PARSER_PROCESS_FAILED);
    private final ProcessingService service;
    private final FileStorage storage;
    private final ProcessingProperties properties;
    private final ParserProcess parser;
    private final ParserWorkspace workspace;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("document-parser").factory());
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("document-parser-lease").factory());

    public ProcessingWorker(ProcessingService service, FileStorage storage, ProcessingProperties properties) {
        this.service = service; this.storage = storage; this.properties = properties;
        workspace = new ParserWorkspace(properties.root());
        parser = new ParserProcess(workspace.current(), properties.timeout());
    }

    @Scheduled(fixedDelayString="${docweave.processing.poll-delay-ms:5000}", initialDelayString="${docweave.processing.poll-delay-ms:5000}")
    public void poll() {
        if (!properties.workerEnabled() || !busy.compareAndSet(false, true)) return;
        try { executor.submit(() -> {
            try { executeOne(); }
            catch (RuntimeException e) { LOG.error("Document processing poll unavailable; durable tasks retained, type={}", e.getClass().getSimpleName(), safeStack(e)); }
            finally { busy.set(false); }
        }); }
        catch (RejectedExecutionException e) { busy.set(false); }
    }

    /** 同步执行一次供测试/受控审核；不绕过数据库领取、身份校验和租约。 */
    public boolean executeOne() {
        try { workspace.recoverRetired(java.time.Instant.now()); }
        catch (IOException e) { LOG.warn("Retired parser workspace cleanup failed; retained for retry"); }
        var claim = service.claimNext();
        if (claim == null) return false;
        Thread task = Thread.currentThread();
        var leaseLost = new AtomicBoolean();
        var active = new AtomicBoolean(true);
        Object lifecycle = new Object();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            boolean lost;
            try {
                lost = !service.renew(claim);
            } catch (RuntimeException e) { lost = true; }
            synchronized (lifecycle) {
                // 迟到心跳不能中断线程后来处理的另一份文档。
                if (lost && active.get()) { leaseLost.set(true); task.interrupt(); }
            }
        }, properties.heartbeat().toMillis(), properties.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        try {
            LOG.info("Document processing started: jobId={}, documentId={}, attempt={}", claim.job().id(), claim.job().document(), claim.job().attempts());
            var file = claim.target();
            try (var input = storage.open(file.key())) {
                var parsed = parser.parse(input, file.bytes(), file.sha256(), file.mime());
                if (!leaseLost.get() && service.publish(claim, parsed)) LOG.info("Document processing succeeded: jobId={}", claim.job().id());
            }
        } catch (ParsingFailure e) {
            if (!leaseLost.get()) service.failed(claim, e.code().name(), TRANSIENT.contains(e.code()));
            LOG.warn("Document parsing failed: jobId={}, code={}", claim.job().id(), e.code());
        } catch (NoSuchFileException e) {
            if (!leaseLost.get()) service.failed(claim, "ORIGINAL_FILE_UNAVAILABLE", false);
        } catch (IOException e) {
            if (!leaseLost.get()) service.failed(claim, "STORAGE_UNAVAILABLE", true);
        } catch (RuntimeException e) {
            // 不把数据库不可用误写为最终失败；租约过期后由持久状态恢复。
            LOG.error("Document processing unavailable; durable lease retained: jobId={}, type={}", claim.job().id(), e.getClass().getSimpleName(), safeStack(e));
        } finally {
            synchronized (lifecycle) {
                active.set(false);
                if (leaseLost.get()) Thread.interrupted();
            }
            heartbeat.cancel(false);
        }
        return true;
    }

    @PreDestroy
    void close() { executor.shutdownNow(); heartbeats.shutdownNow(); }

    private static Throwable safeStack(RuntimeException failure) {
        // SQL 异常消息可能含正文参数，只保留定位代码所需的栈，不复制消息/原因链。
        var sanitized = new IllegalStateException("Document processing failed; details omitted");
        sanitized.setStackTrace(failure.getStackTrace());
        return sanitized;
    }
}
