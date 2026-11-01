package me.asikeida.docweave.knowledgebase.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import me.asikeida.docweave.knowledgebase.dto.*;
import me.asikeida.docweave.organization.dto.PersonalArea;
import me.asikeida.docweave.sample.SampleCatalog;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    public KnowledgeRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public String state(String tenant){return jdbc.queryForObject("SELECT import_state FROM personal_sample_initializations WHERE tenant_id=? FOR UPDATE",String.class,tenant);}
    public String seedVersion(String tenant){return jdbc.queryForObject("SELECT seed_version FROM personal_sample_initializations WHERE tenant_id=?",String.class,tenant);}
    public void importSamples(PersonalArea area,List<SampleCatalog.Preview> samples,Instant now){
        String kb=UUID.randomUUID().toString();Timestamp time=Timestamp.from(now);
        jdbc.update("INSERT INTO knowledge_bases (id,tenant_id,workspace_id,name,seed_version,created_at) VALUES (?,?,?,'示例知识库','v1',?)",kb,area.organizationId(),area.workspaceId(),time);
        for(var sample:samples){
            String doc=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO documents (id,tenant_id,knowledge_base_id,title,sample_key,seed_version,created_at) VALUES (?,?,?,?,?,?,?)",doc,area.organizationId(),kb,sample.sample().title(),sample.sample().id(),sample.sample().version(),time);
            jdbc.update("INSERT INTO document_versions VALUES (?,?,?,1,?,?,'NOT_INDEXED',?)",UUID.randomUUID().toString(),area.organizationId(),doc,sample.content(),sample.sample().sha256(),time);
        }
        jdbc.update("UPDATE personal_sample_initializations SET import_state='IMPORTED_NOT_INDEXED',last_error_code=NULL,next_retry_at=NULL WHERE tenant_id=?",area.organizationId());
    }
    public List<KnowledgeBase> list(PersonalArea area){
        return jdbc.query("""
            SELECT k.id,k.name,k.workspace_id,COUNT(d.id) AS document_count
            FROM knowledge_bases k LEFT JOIN documents d ON d.tenant_id=k.tenant_id AND d.knowledge_base_id=k.id AND d.deleted=FALSE
            WHERE k.tenant_id=? AND k.workspace_id=? AND k.deleted=FALSE GROUP BY k.id,k.name,k.workspace_id ORDER BY k.id
            """,(rs,n)->new KnowledgeBase(rs.getString("id"),rs.getString("name"),rs.getString("workspace_id"),rs.getInt("document_count"),"NOT_INDEXED"),area.organizationId(),area.workspaceId());
    }
    public List<DocumentSummary> documents(String tenant,String kb){
        return jdbc.query("""
            SELECT d.id,d.knowledge_base_id,d.title,d.sample_key,d.seed_version,v.sha256,v.index_state
            FROM documents d JOIN document_versions v ON v.tenant_id=d.tenant_id AND v.document_id=d.id AND v.version_no=1
            WHERE d.tenant_id=? AND d.knowledge_base_id=? AND d.deleted=FALSE ORDER BY d.sample_key
            """,(rs,n)->new DocumentSummary(rs.getString("id"),rs.getString("knowledge_base_id"),rs.getString("title"),rs.getString("sample_key"),rs.getString("seed_version"),rs.getString("sha256"),rs.getString("index_state")),tenant,kb);
    }
    public Optional<String> content(String tenant,String doc){return jdbc.query("SELECT content_text FROM document_versions WHERE tenant_id=? AND document_id=? AND version_no=1",(rs,n)->rs.getString(1),tenant,doc).stream().findFirst();}
    public Optional<DocumentDetail> detail(PersonalArea area,String id){
        return jdbc.query("""
            SELECT d.id,d.knowledge_base_id,d.title,d.sample_key,d.seed_version,v.sha256,v.index_state,v.content_text
            FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id AND k.tenant_id=d.tenant_id
            JOIN document_versions v ON v.document_id=d.id AND v.tenant_id=d.tenant_id AND v.version_no=1
            WHERE d.id=? AND d.tenant_id=? AND k.workspace_id=? AND d.deleted=FALSE AND k.deleted=FALSE
            """,(rs,n)->new DocumentDetail(new DocumentSummary(rs.getString("id"),rs.getString("knowledge_base_id"),rs.getString("title"),rs.getString("sample_key"),rs.getString("seed_version"),rs.getString("sha256"),rs.getString("index_state")),rs.getString("content_text")),id,area.organizationId(),area.workspaceId()).stream().findFirst();
    }
    public List<String> pending(Instant now){
        return jdbc.query("""
            SELECT o.owner_user_id FROM personal_sample_initializations s JOIN organizations o ON o.id=s.tenant_id
            JOIN organization_memberships m ON m.tenant_id=o.id AND m.user_id=o.owner_user_id
            WHERE s.import_state='PENDING' AND o.active=TRUE AND m.active=TRUE AND s.attempt_count<5
            AND (s.next_retry_at IS NULL OR s.next_retry_at<=?) ORDER BY s.created_at,s.tenant_id LIMIT 10
            """,(rs,n)->rs.getString(1),Timestamp.from(now));
    }
    public void failed(String user,Instant retry){jdbc.update("UPDATE personal_sample_initializations SET attempt_count=attempt_count+1,last_error_code='SAMPLE_IMPORT_FAILED',next_retry_at=? WHERE import_state='PENDING' AND tenant_id IN (SELECT id FROM organizations WHERE owner_user_id=?)",Timestamp.from(retry),user);}
}
