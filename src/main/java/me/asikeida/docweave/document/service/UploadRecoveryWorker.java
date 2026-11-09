package me.asikeida.docweave.document.service;

import java.io.IOException;
import java.time.Clock;
import me.asikeida.docweave.document.repository.DocumentRepository;
import me.asikeida.docweave.document.storage.FileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 有界回收：上传租约过期后失败并释放预留，物理清理最多自动尝试八次，错误保留供排查。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name="docweave.documents.worker-enabled",havingValue="true",matchIfMissing=true)
public class UploadRecoveryWorker {
    private final DocumentRepository repository;private final FileStorage storage;private final Clock clock;private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public UploadRecoveryWorker(DocumentRepository repository,FileStorage storage,Clock clock,PlatformTransactionManager manager,org.springframework.jdbc.core.JdbcTemplate jdbc){this.repository=repository;this.storage=storage;this.clock=clock;this.tx=new TransactionTemplate(manager);this.jdbc=jdbc;}
    @Scheduled(fixedDelayString="${docweave.documents.poll-delay-ms:30000}",initialDelayString="${docweave.documents.poll-delay-ms:30000}")
    public void recover(){
        try{
            for(String user:repository.expiredUsers(clock.instant()))tx.executeWithoutResult(s->{
                // 不要求账号 ACTIVE：离职/停用也必须能清理；锁顺序与上传相同。
                jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user);
                for(var upload:repository.expired(user,clock.instant()))repository.fail(upload,clock.instant());
            });
            for(String key:repository.cleanable(clock.instant()))tx.executeWithoutResult(s->{
                if(!repository.lockCleanup(key,clock.instant()))return;
                try{storage.delete(key);repository.cleanupDone(key);}
                catch(IOException e){repository.cleanupFailed(key,clock.instant().plusSeconds(60));org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document cleanup failed; retry retained, storageKey={}",key);}
            });
        }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document recovery unavailable; durable tasks retained");}
    }
}
