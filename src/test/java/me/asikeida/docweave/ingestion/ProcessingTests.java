package me.asikeida.docweave.ingestion;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.auth.service.RegistrationService;
import me.asikeida.docweave.document.domain.DocumentFailure;
import me.asikeida.docweave.document.service.DocumentService;
import me.asikeida.docweave.ingestion.parser.ParsedDocument;
import me.asikeida.docweave.ingestion.repository.ProcessingRepository;
import me.asikeida.docweave.ingestion.repository.ProcessingRepository.*;
import me.asikeida.docweave.ingestion.service.*;
import me.asikeida.docweave.knowledgebase.service.KnowledgeService;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="docweave.processing.heartbeat=1s")
@ActiveProfiles({"test", "processing-test"})
class ProcessingTests {
    private static final Path ROOT = temp();
    private static Path temp() { try { return Files.createTempDirectory("docweave-processing-test-"); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("docweave.documents.root", () -> ROOT.resolve("files").toString());
        registry.add("docweave.processing.root", () -> ROOT.resolve("parser").toString());
    }
    @Autowired DocumentService files;
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingService service;
    @Autowired ProcessingWorker worker;
    @Autowired RegistrationService registration;
    @Autowired UserAccountRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProcessingRepository repository;
    @MockitoSpyBean me.asikeida.docweave.document.storage.FileStorage storage;
    private final Set<String> owners = ConcurrentHashMap.newKeySet();
    record Owner(String user, String tenant, String kb) { }

    @BeforeEach void onlyClaimThisTestsFixtures() {
        // CI 共用隔离数据库；只缩小候选选择，领取/锁/续租/发布 SQL 和解析进程仍真实执行。
        doAnswer(invocation -> {
            var result = new ArrayList<Job>();
            for (String user : owners) {
                var rows = jdbc.queryForList("SELECT j.tenant_id,j.version_id FROM document_processing_jobs j JOIN organizations o ON o.id=j.tenant_id WHERE o.owner_user_id=?", user);
                for (var row : rows) repository.jobFor(row.get("tenant_id").toString(), row.get("version_id").toString()).ifPresent(result::add);
            }
            return result;
        }).when(repository).candidates(any());
    }

    @Test void uploadQueuesAtomicallyAndWorkerPublishesRealTextWithoutChangingFileHash() throws Exception {
        var owner = owner(); String idem = UUID.randomUUID().toString();
        var source = source("# 正文😀\r\n第二行");
        var uploaded = files.upload(owner.user(), owner.kb(), idem, source);
        String doc = uploaded.document().id(), rawHash = uploaded.document().sha256();
        assertThat(service.state(owner.user(), doc)).isEqualTo("PENDING");
        assertThat(files.upload(owner.user(), owner.kb(), idem, source).document().id()).isEqualTo(doc);
        service.enqueue(owner.user(), doc);
        assertThat(count("document_processing_jobs", owner.tenant())).isEqualTo(1);
        assertThat(worker.executeOne()).isTrue();
        assertThat(service.state(owner.user(), doc)).isEqualTo("SUCCEEDED");
        var detail = knowledge.document(owner.user(), doc);
        assertThat(detail.content()).isEqualTo("# 正文😀\n第二行");
        assertThat(detail.document().source()).isEqualTo("UPLOAD");
        assertThat(detail.document().sha256()).isEqualTo(rawHash);
        assertThat(detail.document().indexState()).isEqualTo("NOT_INDEXED");
        assertThat(count("document_text_locations", owner.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM document_versions WHERE tenant_id=? AND document_id=?", String.class, owner.tenant(), doc)).hasSize(64).isNotEqualTo(rawHash);
        try (var stream = files.download(owner.user(), doc).input()) { assertThat(stream.readAllBytes()).isEqualTo(source.getBytes()); }
        assertThat(worker.executeOne()).isFalse();
        assertThat(knowledge.documents(owner.user(), owner.kb()).stream().filter(d -> d.source().equals("SAMPLE"))).hasSize(2);
    }

    @Test void pendingLimitRollsBackDocumentAndRetainsDurableCleanup() {
        var owner = owner();
        for (int i = 0; i < 10; i++) upload(owner, "fixture " + i);
        assertThatThrownBy(() -> upload(owner, "eleventh fixture")).isInstanceOfSatisfying(DocumentFailure.class,
                e -> assertThat(e.getCode()).isEqualTo("PROCESSING_QUEUE_FULL"));
        assertThat(count("document_processing_jobs", owner.tenant())).isEqualTo(10);
        assertThat(knowledge.documents(owner.user(), owner.kb())).hasSize(12);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_uploads WHERE tenant_id=? AND status='FAILED'", Integer.class, owner.tenant())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_cleanup_jobs c JOIN document_uploads u ON u.storage_key=c.storage_key WHERE u.tenant_id=?", Integer.class, owner.tenant())).isEqualTo(1);
    }

    @Test void oldLeaseCannotPublishOrRenewAfterRecovery() {
        var owner = owner(); String doc = upload(owner, "原文件"); Claim old = service.claimNext(); assertThat(old).isNotNull();
        jdbc.update("UPDATE document_processing_jobs SET lease_expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(5)), old.job().id());
        assertThat(service.claimNext()).isNull();
        assertThat(service.state(owner.user(), doc)).isEqualTo("PENDING");
        assertThat(service.publish(old, parsed("旧结果"))).isFalse(); assertThat(service.renew(old)).isFalse();
        ready(old.job().id()); Claim current = service.claimNext();
        assertThat(current.job().token()).isNotEqualTo(old.job().token());
        assertThat(current.job().attempts()).isEqualTo(2);
        assertThat(service.publish(old, parsed("迟到结果"))).isFalse();
        assertThat(service.publish(current, parsed("有效结果"))).isTrue();
        assertThat(knowledge.document(owner.user(), doc).content()).isEqualTo("有效结果");
    }

    @Test void deletionAndMembershipRevocationPreventLatePublish() {
        var one = owner(); String deleted = upload(one, "删除竞争"); var old = service.claimNext();
        files.delete(one.user(), deleted);
        assertThat(job(one, deleted).status()).isEqualTo("CANCELLED");
        assertThat(service.publish(old, parsed("不能复活"))).isFalse();
        assertThatThrownBy(() -> knowledge.document(one.user(), deleted)).isInstanceOf(RuntimeException.class);
        var two = owner(); String revoked = upload(two, "成员撤权"); var claim = service.claimNext();
        jdbc.update("UPDATE organization_memberships SET active=FALSE WHERE tenant_id=?", two.tenant());
        assertThat(service.publish(claim, parsed("不得发布"))).isFalse();
        assertThat(job(two, revoked).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_versions WHERE tenant_id=? AND document_id=?", String.class, two.tenant(), revoked)).isNull();
    }

    @Test void disabledAccountAndCrossTenantRequestsAreDenied() {
        var one = owner(); var two = owner(); String doc = upload(one, "隔离正文");
        assertThatThrownBy(() -> service.enqueue(two.user(), doc)).isInstanceOf(DocumentFailure.class);
        assertThatThrownBy(() -> service.state(two.user(), doc)).isInstanceOf(DocumentFailure.class);
        var claim = service.claimNext();
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", one.user());
        assertThat(service.publish(claim, parsed("停用后不得发布"))).isFalse();
        assertThat(job(one, doc).status()).isEqualTo("CANCELLED");
    }

    @Test void publicationFailureRollsBackTextLocationsAndSuccessTogether() {
        var owner = owner(); String doc = upload(owner, "事务验证"); var claim = service.claimNext();
        var fault = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (fault.getAndSet(false)) throw new DataIntegrityViolationException("injected final commit failure");
            return null;
        }).when(repository).publish(any(), any(), anyString(), any());
        assertThatThrownBy(() -> service.publish(claim, parsed("不能部分可见"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(job(owner, doc).status()).isEqualTo("RUNNING");
        assertThat(count("document_text_locations", owner.tenant())).isZero();
        assertThatThrownBy(() -> knowledge.document(owner.user(), doc)).isInstanceOf(DocumentFailure.class);
        assertThat(service.publish(claim, parsed("完整提交"))).isTrue();
        assertThat(knowledge.document(owner.user(), doc).content()).isEqualTo("完整提交");
    }

    @Test void retriesAreDurableBoundedAndNonRetryableFailureStops() {
        var owner = owner(); String doc = upload(owner, "重试原文件");
        for (int attempt = 1; attempt <= 5; attempt++) {
            var claim = service.claimNext(); assertThat(claim).isNotNull();
            assertThat(claim.job().attempts()).isEqualTo(attempt);
            service.failed(claim, "STORAGE_UNAVAILABLE", true);
            var job = job(owner, doc);
            assertThat(job.status()).isEqualTo(attempt == 5 ? "FAILED" : "PENDING");
            if (attempt < 5) { assertThat(job.nextAttempt()).isAfter(Instant.now()); ready(job.id()); }
        }
        assertThat(service.claimNext()).isNull();
        assertThat(service.enqueue(owner.user(), doc).attempts()).isEqualTo(5);
        String second = upload(owner, "不同文档"); var claim = service.claimNext();
        service.failed(claim, "NO_EXTRACTABLE_TEXT", false);
        assertThat(job(owner, second).status()).isEqualTo("FAILED");
        assertThat(service.claimNext()).isNull();
    }

    @Test void concurrentClaimersOnlyOwnOneTokenAndRenewalExtendsCurrentLease() throws Exception {
        var owner = owner(); upload(owner, "并发领取");
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            Callable<Claim> operation = () -> { gate.await(); return service.claimNext(); };
            var first = pool.submit(operation); var second = pool.submit(operation); gate.countDown();
            var a = first.get(10, TimeUnit.SECONDS); var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a == null ^ b == null).isTrue(); var claim = a == null ? b : a;
            assertThat(service.renew(claim)).isTrue();
            assertThat(repository.lockJob(claim.job().tenant(), claim.job().id()).orElseThrow().attempts()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void lostLeaseInterruptsBlockedCopyAndCannotPublishDeletedDocument() throws Exception {
        var owner = owner(); String doc = upload(owner, "模拟读取中删除");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> new java.io.FilterInputStream((java.io.InputStream) invocation.callRealMethod()) {
            @Override public int read(byte[] bytes, int offset, int count) throws java.io.IOException {
                entered.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                catch (InterruptedException e) { throw new java.io.IOException("fixture copy interrupted"); }
                return super.read(bytes, offset, count);
            }
        }).when(storage).open(anyString());
        var pool = Executors.newSingleThreadExecutor();
        try {
            var execution = pool.submit(() -> worker.executeOne());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            files.delete(owner.user(), doc);
            assertThat(execution.get(8, TimeUnit.SECONDS)).isTrue();
            assertThat(job(owner, doc).status()).isEqualTo("CANCELLED");
            assertThat(count("document_text_locations", owner.tenant())).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private Owner owner() {
        String username = "proc-" + UUID.randomUUID().toString().substring(0, 8);
        var area = registration.register(new RegistrationRequest(username, "processing-password-123", "解析用户"));
        String user = users.findByUsername(username).orElseThrow().getId(); owners.add(user);
        return new Owner(user, area.organizationId(), knowledge.initialize(user).getFirst().id());
    }
    private MockMultipartFile source(String body) { return new MockMultipartFile("file", "资料.md", "text/markdown", body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String upload(Owner owner, String body) { return files.upload(owner.user(), owner.kb(), UUID.randomUUID().toString(), source(body)).document().id(); }
    private int count(String table, String tenant) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private Job job(Owner owner, String doc) {
        String version = jdbc.queryForObject("SELECT id FROM document_versions WHERE tenant_id=? AND document_id=?", String.class, owner.tenant(), doc);
        return repository.jobFor(owner.tenant(), version).orElseThrow();
    }
    private void ready(String id) { jdbc.update("UPDATE document_processing_jobs SET next_attempt_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id); }
    private ParsedDocument parsed(String text) { return new ParsedDocument(text, List.of(new ParsedDocument.Location(0, null, 1, 1, 0, text.length()))); }
}
