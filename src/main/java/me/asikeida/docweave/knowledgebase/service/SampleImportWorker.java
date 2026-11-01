package me.asikeida.docweave.knowledgebase.service;

import java.time.Clock;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import me.asikeida.docweave.knowledgebase.repository.KnowledgeRepository;

/** 数据库待办的有界消费者；重启可继续，每次最多10项，自动失败最多5次，仍可手动重试。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="docweave.samples.worker-enabled",havingValue="true",matchIfMissing=true)
public class SampleImportWorker {
    private static final Logger log=LoggerFactory.getLogger(SampleImportWorker.class);
    private final KnowledgeRepository repository;private final KnowledgeService service;private final Clock clock;
    public SampleImportWorker(KnowledgeRepository repository,KnowledgeService service,Clock clock){this.repository=repository;this.service=service;this.clock=clock;}
    @Scheduled(fixedDelayString="${docweave.samples.poll-delay-ms:30000}",initialDelayString="${docweave.samples.poll-delay-ms:30000}")
    public void consume(){
        try{
            for(String user:repository.pending(clock.instant())){
                try{service.initialize(user);}catch(RuntimeException failure){repository.failed(user,clock.instant().plusSeconds(60));log.warn("Sample import failed; retry recorded, userId={}",user);}
            }
        }catch(RuntimeException failure){log.warn("Sample import poll unavailable; will retry");}
    }
}
