package me.asikeida.docweave.document.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.document.config.DocumentProperties;
import me.asikeida.docweave.document.domain.DocumentFailure;
import me.asikeida.docweave.document.repository.DocumentRepository;
import me.asikeida.docweave.document.repository.DocumentRepository.Upload;
import me.asikeida.docweave.document.storage.FileStorage;
import me.asikeida.docweave.knowledgebase.dto.DocumentSummary;
import me.asikeida.docweave.knowledgebase.service.KnowledgeService;
import me.asikeida.docweave.organization.repository.PersonalAreaRepository;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 短事务预留与确认；外部文件 I/O 不占用上传事务，任何文件先有持久上传记录。 */
@Service
public class DocumentService {
    private final DocumentRepository files;private final PersonalAreaRepository personal;private final PersonalAreaService areas;
    private final KnowledgeService knowledge;private final FileStorage storage;private final FileInspection inspection;
    private final DocumentProperties properties;private final Clock clock;private final TransactionTemplate tx;
    private final me.asikeida.docweave.ingestion.service.ProcessingService processing;
    public DocumentService(DocumentRepository files,PersonalAreaRepository personal,PersonalAreaService areas,KnowledgeService knowledge,
            FileStorage storage,FileInspection inspection,DocumentProperties properties,Clock clock,PlatformTransactionManager manager,
            me.asikeida.docweave.ingestion.service.ProcessingService processing){
        this.files=files;this.personal=personal;this.areas=areas;this.knowledge=knowledge;this.storage=storage;
        this.inspection=inspection;this.properties=properties;this.clock=clock;this.tx=new TransactionTemplate(manager);
        this.processing=processing;
    }
    private String authorized(String user,String kb){
        if(!personal.lockActiveUser(user))throw AuthFailure.invalidLogin();
        knowledge.knowledgeBase(user,kb);return areas.current(user).organizationId();
    }
    private void writable(){if(!properties.writeEnabled())throw new DocumentFailure("DOCUMENT_WRITES_DISABLED","当前环境暂未开放文件写入",403);}
    public record Result(DocumentSummary document,boolean replayed) { }
    private record Completed(String id,boolean reused) { }
    public Result upload(String user,String kb,String idem,MultipartFile source){
        writable();
        tx.executeWithoutResult(s->authorized(user,kb));
        if(idem==null || !idem.matches("[A-Za-z0-9_-]{8,80}"))throw new DocumentFailure("INVALID_IDEMPOTENCY_KEY","需提供8至80位幂等键",400);
        var file=inspection.inspect(source);
        String fingerprint=hash(kb+"\n"+file.name()+"\n"+file.size()+"\n"+file.mime()+"\n"+file.sha256());
        Upload attempt=tx.execute(s->{
            String tenant=authorized(user,kb);
            var previous=files.attempt(user,idem);
            if(previous.isPresent()){
                var value=previous.get();
                if(!value.fingerprint().equals(fingerprint))throw new DocumentFailure("IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同文件",409);
                if(value.status().equals("COMPLETE"))return value;
                if(value.status().equals("WRITING"))throw new DocumentFailure("UPLOAD_IN_PROGRESS","上传正在处理，请使用同一幂等键稍后重试",409);
                throw new DocumentFailure("UPLOAD_PREVIOUSLY_FAILED","此前上传失败，请使用新的幂等键重试",409);
            }
            Instant now=clock.instant();
            if(files.inFlight(user)>=properties.maxConcurrent() || files.daily(user,now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC))>=properties.dailyLimit())throw new DocumentFailure("UPLOAD_RATE_LIMITED","上传次数或并发已达上限",429);
            if(files.used(user)+files.reserved(user)+file.size()>properties.maxUserBytes())throw new DocumentFailure("STORAGE_QUOTA_EXCEEDED","个人文件容量不足",429);
            return files.reserve(user,tenant,kb,idem,fingerprint,file,now,now.plus(properties.uploadLease()));
        });
        if(attempt.status().equals("COMPLETE"))return new Result(summary(user,kb,attempt.documentId()),true);
        try {
            try(InputStream in=source.getInputStream()){storage.write(attempt.key(),in,file.size(),file.sha256());}
            Completed completed=tx.execute(s->{
                String tenant=authorized(user,kb);Instant now=clock.instant();
                if(!files.writing(attempt.id(),now))throw new DocumentFailure("UPLOAD_EXPIRED","上传已过期，请重试",409);
                var duplicate=files.duplicate(tenant,kb,file.sha256());
                if(duplicate.isPresent()){
                    files.complete(attempt.id(),duplicate.get());files.cleanup(attempt.key(),now);return new Completed(duplicate.get(),true);
                }
                String document=files.create(user,tenant,kb,attempt,file,now);
                processing.enqueue(user,document);
                return new Completed(document,false);
            });
            return new Result(summary(user,kb,completed.id()),completed.reused());
        } catch(Exception e){
            try{tx.executeWithoutResult(s->{personal.lockActiveUser(user);files.fail(attempt,clock.instant());});}
            catch(RuntimeException cleanup){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Upload finalization unavailable; lease recovery will retry, uploadId={}",attempt.id());}
            if(e instanceof DocumentFailure failure)throw failure;
            if(e instanceof AuthFailure failure)throw failure;
            throw DocumentFailure.unavailable();
        }
    }
    private DocumentSummary summary(String user,String kb,String id){
        var document=knowledge.documents(user,kb).stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(()->new DocumentFailure("RESOURCE_NOT_FOUND","资源不存在或不可访问",404));
        var file=files.file(areas.current(user).organizationId(),id);
        if(file==null)throw DocumentFailure.unavailable();
        // 包括幂等重放和去重：对象缺失时不能只凭 COMPLETE 元数据报告成功。
        try(var ignored=storage.open(file.key())){return document;}
        catch(IOException e){throw DocumentFailure.unavailable();}
    }
    public record Download(InputStream input,String name,long size,String mime){ }
    public Download download(String user,String id){
        var file=tx.execute(s->{
            String tenant=areas.current(user).organizationId();
            var owned=files.owned(tenant,id).filter(d->!d.deleted()).orElseThrow(()->notFound());authorized(user,owned.kb());
            var found=files.file(tenant,id);
            if(found==null)throw new DocumentFailure("ORIGINAL_FILE_UNAVAILABLE","该示例没有原始文件",404);
            return found;
        });
        try{return new Download(storage.open(file.key()),file.name(),file.size(),file.mime());}
        catch(IOException e){throw DocumentFailure.unavailable();}
    }
    public void delete(String user,String id){
        writable();tx.executeWithoutResult(s->{
            String tenant=areas.current(user).organizationId();var owned=files.owned(tenant,id).orElseThrow(()->notFound());authorized(user,owned.kb());
            if(owned.deleted())return;
            processing.cancelForDeletion(tenant,id);
            files.delete(tenant,id);var file=files.file(tenant,id);if(file!=null)files.cleanup(file.key(),clock.instant());
        });
    }
    private static DocumentFailure notFound(){return new DocumentFailure("RESOURCE_NOT_FOUND","资源不存在或不可访问",404);}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
