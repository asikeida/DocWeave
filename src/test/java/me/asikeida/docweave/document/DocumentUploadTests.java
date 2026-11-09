package me.asikeida.docweave.document;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.asikeida.docweave.auth.dto.RegistrationRequest;
import me.asikeida.docweave.auth.service.*;
import me.asikeida.docweave.document.config.DocumentProperties;
import me.asikeida.docweave.document.domain.DocumentFailure;
import me.asikeida.docweave.document.repository.DocumentRepository;
import me.asikeida.docweave.document.service.*;
import me.asikeida.docweave.document.storage.*;
import me.asikeida.docweave.knowledgebase.service.KnowledgeService;
import me.asikeida.docweave.user.repository.UserAccountRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(DocumentUploadTests.StorageConfig.class)
class DocumentUploadTests {
    static final Path root=createRoot();
    private static Path createRoot(){try{return Files.createTempDirectory("docweave-file-test-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("docweave.documents.root",()->root.toString());}
    @Autowired DocumentService service;@Autowired KnowledgeService knowledge;@Autowired RegistrationService registration;
    @Autowired UserAccountRepository users;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired AuthService auth;
    @Autowired ControlledStorage storage;@MockitoSpyBean DocumentRepository repository;@Autowired Clock clock;@Autowired PlatformTransactionManager manager;
    @Autowired DocumentProperties properties;
    record Owner(String id,String username,String kb){ }
    Owner owner(){String name="file-"+UUID.randomUUID().toString().substring(0,8);registration.register(new RegistrationRequest(name,"file-password-123","文件用户"));String id=users.findByUsername(name).orElseThrow().getId();return new Owner(id,name,knowledge.initialize(id).getFirst().id());}
    MockMultipartFile file(){return new MockMultipartFile("file","资料.md","text/markdown","# 文档\n供上传测试的独立资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    String key(){return UUID.randomUUID().toString();}
    UploadRecoveryWorker worker(){return new UploadRecoveryWorker(repository,storage,clock,manager,jdbc);}
    @BeforeEach void resetFaults(){storage.failWrite.set(false);storage.failDelete.set(false);reset(repository);}

    @Test void uploadDownloadAndLogicalDeleteAreDurableAndIsolated() throws Exception {
        var one=owner();var two=owner();var result=service.upload(one.id(),one.kb(),key(),file());String id=result.document().id();
        assertThat(result.document().source()).isEqualTo("UPLOAD");assertThat(result.document().sampleKey()).isNull();
        assertThat(result.document().byteSize()).isEqualTo(file().getSize());assertThat(knowledge.documents(one.id(),one.kb())).hasSize(3);
        try(var stream=service.download(one.id(),id).input()){assertThat(stream.readAllBytes()).isEqualTo(file().getBytes());}
        assertThatThrownBy(()->knowledge.document(one.id(),id)).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("DOCUMENT_NOT_PARSED"));
        assertThatThrownBy(()->service.download(two.id(),id)).isInstanceOf(DocumentFailure.class);
        assertThatThrownBy(()->service.delete(two.id(),id)).isInstanceOf(DocumentFailure.class);
        assertThatThrownBy(()->service.upload(two.id(),one.kb(),key(),file())).isInstanceOf(AuthFailure.class);
        // 新建存储实现读取同一目录，验证重建实例不丢原文件。
        String tenant=jdbc.queryForObject("SELECT tenant_id FROM knowledge_bases WHERE id=?",String.class,one.kb());
        var actual=repository.file(tenant,id);
        try(var stream=new LocalFileStorage(properties).open(actual.key())){assertThat(stream.readAllBytes()).isEqualTo(file().getBytes());}
        service.delete(one.id(),id);service.delete(one.id(),id);
        assertThatThrownBy(()->service.download(one.id(),id)).isInstanceOf(DocumentFailure.class);
        worker().recover();assertThat(knowledge.documents(one.id(),one.kb())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_cleanup_jobs WHERE status='PENDING' AND storage_key IN (SELECT storage_key FROM document_files WHERE document_id=?)",Integer.class,id)).isZero();
    }
    @Test void retriesDuplicatesAndConcurrentCompletionCreateOnlyOneDocument() throws Exception {
        var owner=owner();String idem=key();var first=service.upload(owner.id(),owner.kb(),idem,file());
        assertThat(service.upload(owner.id(),owner.kb(),idem,file()).replayed()).isTrue();
        var different=new MockMultipartFile("file","资料.md","text/markdown","不同内容".getBytes());
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),idem,different)).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->service.upload(owner.id(),owner.kb(),key(),file()));
            var b=executor.submit(()->service.upload(owner.id(),owner.kb(),key(),file()));
            assertThat(a.get().document().id()).isEqualTo(first.document().id());assertThat(b.get().document().id()).isEqualTo(first.document().id());
        }
        assertThat(knowledge.documents(owner.id(),owner.kb())).hasSize(3);
        service.delete(owner.id(),first.document().id());
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),idem,file())).isInstanceOf(DocumentFailure.class);
        assertThat(service.upload(owner.id(),owner.kb(),key(),file()).document().id()).isNotEqualTo(first.document().id());worker().recover();
    }
    @Test void publishedObjectFailureIsPersistedAndDeletionCanRetry() {
        var owner=owner();storage.failWrite.set(true);String idem=key();
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),idem,file())).isInstanceOf(DocumentFailure.class);
        var attempt=repository.attempt(owner.id(),idem).orElseThrow();assertThat(attempt.status()).isEqualTo("FAILED");assertThat(Files.exists(root.resolve(attempt.key()+".bin"))).isTrue();
        assertThat(repository.cleanable(clock.instant())).as("cleanup rows %s; now=%s",jdbc.queryForList("SELECT * FROM document_cleanup_jobs WHERE storage_key=?",attempt.key()),clock.instant()).contains(attempt.key());
        storage.failDelete.set(true);worker().recover();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM document_cleanup_jobs WHERE storage_key=?",String.class,attempt.key())).isEqualTo("STORAGE_DELETE_FAILED");
        storage.failDelete.set(false);jdbc.update("UPDATE document_cleanup_jobs SET next_retry_at=? WHERE storage_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),attempt.key());worker().recover();
        assertThat(Files.exists(root.resolve(attempt.key()+".bin"))).isFalse();assertThat(knowledge.documents(owner.id(),owner.kb())).hasSize(2);
    }
    @Test void expiredReservationsReleaseQuotaAndCleanPartialFiles() throws Exception {
        var owner=owner();String tenant=jdbc.queryForObject("SELECT tenant_id FROM knowledge_bases WHERE id=?",String.class,owner.kb());
        var info=new FileInspection.Inspected("a.txt",1,"text/plain","0".repeat(64));
        var upload=repository.reserve(owner.id(),tenant,owner.kb(),key(),"a".repeat(64),info,Instant.now().minusSeconds(600),Instant.now().minusSeconds(1));
        Files.writeString(root.resolve(upload.key()+".part"),"x");worker().recover();
        assertThat(repository.inFlight(owner.id())).isZero();assertThat(Files.exists(root.resolve(upload.key()+".part"))).isFalse();
    }
    @Test void rejectsUnsafeFilesAndPreservesSampleDownloadSemantics() {
        var owner=owner();
        for(var bad:List.of(new MockMultipartFile("file","../x.txt","text/plain","x".getBytes()),new MockMultipartFile("file","a.txt","text/plain",new byte[0]),new MockMultipartFile("file","a.pdf","application/pdf","fake".getBytes()),new MockMultipartFile("file","a.txt","text/plain",new byte[]{0,1}),new MockMultipartFile("file","a.exe","text/plain","x".getBytes()))){
            assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),bad)).isInstanceOf(DocumentFailure.class);
        }
        assertThatThrownBy(()->service.download(owner.id(),knowledge.documents(owner.id(),owner.kb()).getFirst().id())).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("ORIGINAL_FILE_UNAVAILABLE"));
    }
    @Test void databaseFinalizationFailureRollsBackMetadataAndRetainsCleanup() {
        var owner=owner();String idem=key();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("injected transaction failure")).when(repository).create(anyString(),anyString(),anyString(),any(),any(),any());
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),idem,file())).isInstanceOf(DocumentFailure.class);
        assertThat(knowledge.documents(owner.id(),owner.kb())).hasSize(2);
        var upload=repository.attempt(owner.id(),idem).orElseThrow();assertThat(upload.status()).isEqualTo("FAILED");
        worker().recover();assertThat(Files.exists(root.resolve(upload.key()+".bin"))).isFalse();
    }
    @Test void concurrentReservationsAndTotalCapacityAreEnforced() {
        var owner=owner();String tenant=jdbc.queryForObject("SELECT tenant_id FROM knowledge_bases WHERE id=?",String.class,owner.kb());
        var info=new FileInspection.Inspected("a.txt",1,"text/plain","0".repeat(64));
        var a=repository.reserve(owner.id(),tenant,owner.kb(),key(),"a".repeat(64),info,Instant.now(),Instant.now().plusSeconds(900));
        var b=repository.reserve(owner.id(),tenant,owner.kb(),key(),"b".repeat(64),info,Instant.now(),Instant.now().plusSeconds(900));
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),file())).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("UPLOAD_RATE_LIMITED"));
        repository.fail(a,Instant.now());repository.fail(b,Instant.now());
        var doc=service.upload(owner.id(),owner.kb(),key(),file()).document();
        jdbc.update("UPDATE document_files SET byte_size=? WHERE document_id=?",properties.maxUserBytes(),doc.id());
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),file())).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("STORAGE_QUOTA_EXCEEDED"));
        jdbc.update("UPDATE document_files SET byte_size=? WHERE document_id=?",file().getSize(),doc.id());
    }
    @Test void httpRequiresCsrfAndProvidesBinaryAttachmentAndRequestId() throws Exception {
        var owner=owner();String token=auth.login(owner.username(),"file-password-123",null).response().accessToken();
        mvc.perform(multipart("/api/v1/knowledge-bases/"+owner.kb()+"/documents").file(file()).header("Authorization","Bearer "+token).header("Idempotency-Key",key())).andExpect(status().isForbidden());
        var uploaded=service.upload(owner.id(),owner.kb(),key(),file()).document();
        mvc.perform(get("/api/v1/documents/"+uploaded.id()+"/download").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id")).andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(file().getBytes()));
        mvc.perform(delete("/api/v1/documents/"+uploaded.id()).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void acceptsStructuralPdfAndRejectsEncryptedAndOversizedFiles() throws Exception {
        var owner=owner();
        byte[] pdf;
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var out=new ByteArrayOutputStream()){
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());document.save(out);pdf=out.toByteArray();
        }
        var result=service.upload(owner.id(),owner.kb(),key(),new MockMultipartFile("file","结构有效.pdf","application/pdf",pdf));
        assertThat(result.document().mediaType()).isEqualTo("application/pdf");
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var out=new ByteArrayOutputStream()){
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-password","reader-password",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(out);var encrypted=new MockMultipartFile("file","加密.pdf","application/pdf",out.toByteArray());
            assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),encrypted)).isInstanceOf(DocumentFailure.class);
        }
        var oversized=new MockMultipartFile("file","large.txt","text/plain",new byte[0]){@Override public long getSize(){return properties.maxFileBytes()+1;}};
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),oversized)).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getStatus()).isEqualTo(413));
    }
    @Test void dailyAttemptLimitIsPersistent() {
        var owner=owner();String tenant=jdbc.queryForObject("SELECT tenant_id FROM knowledge_bases WHERE id=?",String.class,owner.kb());
        var info=new FileInspection.Inspected("a.txt",1,"text/plain","0".repeat(64));
        for(int i=0;i<properties.dailyLimit();i++){
            var upload=repository.reserve(owner.id(),tenant,owner.kb(),key(),"a".repeat(64),info,Instant.now(),Instant.now().plusSeconds(900));repository.fail(upload,Instant.now());
        }
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),key(),file())).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("UPLOAD_RATE_LIMITED"));
        worker().recover();
    }
    @Test void lostObjectCannotBeReportedAsSuccessfulReplay() throws Exception {
        var owner=owner();String idem=key();var result=service.upload(owner.id(),owner.kb(),idem,file());
        String tenant=jdbc.queryForObject("SELECT tenant_id FROM knowledge_bases WHERE id=?",String.class,owner.kb());
        storage.delete(repository.file(tenant,result.document().id()).key());
        assertThatThrownBy(()->service.upload(owner.id(),owner.kb(),idem,file())).isInstanceOfSatisfying(DocumentFailure.class,e->assertThat(e.getCode()).isEqualTo("STORAGE_UNAVAILABLE"));
    }
    @TestConfiguration static class StorageConfig {
        @Bean @Primary ControlledStorage controlled(LocalFileStorage delegate){return new ControlledStorage(delegate);}
    }
    static class ControlledStorage implements FileStorage {
        final LocalFileStorage delegate;final AtomicBoolean failWrite=new AtomicBoolean(),failDelete=new AtomicBoolean();
        ControlledStorage(LocalFileStorage delegate){this.delegate=delegate;}
        public void write(String key,InputStream in,long size,String sha)throws IOException{delegate.write(key,in,size,sha);if(failWrite.get())throw new IOException("test after publish");}
        public InputStream open(String key)throws IOException{return delegate.open(key);}
        public void delete(String key)throws IOException{if(failDelete.get())throw new IOException("test delete");delegate.delete(key);}
    }
}
