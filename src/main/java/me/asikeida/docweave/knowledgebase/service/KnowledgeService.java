package me.asikeida.docweave.knowledgebase.service;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import me.asikeida.docweave.auth.service.AuthFailure;
import me.asikeida.docweave.organization.repository.PersonalAreaRepository;
import me.asikeida.docweave.organization.service.PersonalAreaService;
import me.asikeida.docweave.knowledgebase.repository.KnowledgeRepository;
import me.asikeida.docweave.knowledgebase.dto.*;
import me.asikeida.docweave.sample.SampleCatalog;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;private final PersonalAreaRepository personal;private final PersonalAreaService areas;private final SampleCatalog catalog;private final Clock clock;
    public KnowledgeService(KnowledgeRepository repository,PersonalAreaRepository personal,PersonalAreaService areas,SampleCatalog catalog,Clock clock){this.repository=repository;this.personal=personal;this.areas=areas;this.catalog=catalog;this.clock=clock;}
    /** 独立于注册提交的事务；用户行锁+待办行锁串行化重试，完成标志不会因删除而回退。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<KnowledgeBase> initialize(String user){
        if(!personal.lockActiveUser(user)){throw AuthFailure.invalidLogin();}
        var area=areas.current(user);
        if("PENDING".equals(repository.state(area.organizationId()))){
            if(!"v1".equals(repository.seedVersion(area.organizationId()))){throw new AuthFailure("SEED_VERSION_UNAVAILABLE","示例版本暂不可用",503);}
            repository.importSamples(area,catalog.list().stream().map(item->catalog.preview(item.id())).toList(),clock.instant());
        }
        return repository.list(area);
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public List<KnowledgeBase> list(String user){return repository.list(areas.current(user));}
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public KnowledgeBase knowledgeBase(String user,String id){return list(user).stream().filter(k->k.id().equals(id)).findFirst().orElseThrow(KnowledgeService::notFound);}
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public List<DocumentSummary> documents(String user,String kb){knowledgeBase(user,kb);return repository.documents(areas.current(user).organizationId(),kb);}
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public DocumentDetail document(String user,String id){
        var detail=repository.detail(areas.current(user),id).orElseThrow(KnowledgeService::notFound);
        if(detail.content()==null)throw new me.asikeida.docweave.document.domain.DocumentFailure("DOCUMENT_NOT_PARSED","原文件已保存，正文尚未解析成功",409);
        return detail;
    }
    private static AuthFailure notFound(){return new AuthFailure("RESOURCE_NOT_FOUND","资源不存在或不可访问",404);}
}
