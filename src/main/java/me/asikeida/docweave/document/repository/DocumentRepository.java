package me.asikeida.docweave.document.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import me.asikeida.docweave.document.service.FileInspection.Inspected;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 文件与上传待办的持久状态；所有业务读取显式带当前组织范围。 */
@Repository
public class DocumentRepository {
    public record Upload(String id,String key,String fingerprint,String status,String documentId,Instant expires) { }
    public record File(String documentId,String key,String name,long size,String mime,String sha256) {
        @Override public String toString(){return "DocumentFile[documentId="+documentId+"]";}
    }
    private final JdbcTemplate jdbc;
    public DocumentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Upload> attempt(String user,String idem){return jdbc.query("SELECT * FROM document_uploads WHERE user_id=? AND idempotency_key=?",(r,n)->new Upload(r.getString("id"),r.getString("storage_key"),r.getString("fingerprint"),r.getString("status"),r.getString("document_id"),r.getTimestamp("expires_at").toInstant()),user,idem).stream().findFirst();}
    public Upload reserve(String user,String tenant,String kb,String idem,String fingerprint,Inspected file,Instant now,Instant expires){
        String id=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO document_uploads (id,user_id,tenant_id,knowledge_base_id,idempotency_key,fingerprint,storage_key,
            original_name,byte_size,media_type,sha256,status,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'WRITING',?,?)
            """,id,user,tenant,kb,idem,fingerprint,key,file.name(),file.size(),file.mime(),file.sha256(),Timestamp.from(expires),Timestamp.from(now));
        return new Upload(id,key,fingerprint,"WRITING",null,expires);
    }
    public long used(String user){return jdbc.queryForObject("SELECT COALESCE(SUM(f.byte_size),0) FROM document_files f JOIN documents d ON d.id=f.document_id AND d.tenant_id=f.tenant_id WHERE f.created_by=? AND d.deleted=FALSE",Long.class,user);}
    public long reserved(String user){return jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM document_uploads WHERE user_id=? AND status='WRITING'",Long.class,user);}
    public int inFlight(String user){return jdbc.queryForObject("SELECT COUNT(*) FROM document_uploads WHERE user_id=? AND status='WRITING'",Integer.class,user);}
    public int daily(String user,Instant start){return jdbc.queryForObject("SELECT COUNT(*) FROM document_uploads WHERE user_id=? AND created_at>=?",Integer.class,user,Timestamp.from(start));}
    public Optional<String> duplicate(String tenant,String kb,String hash){return jdbc.query("SELECT id FROM documents WHERE tenant_id=? AND knowledge_base_id=? AND active_file_hash=? AND deleted=FALSE",(r,n)->r.getString(1),tenant,kb,hash).stream().findFirst();}
    public File file(String tenant,String id){return jdbc.query("""
        SELECT f.*,v.sha256 FROM document_files f JOIN document_versions v ON v.id=f.version_id AND v.tenant_id=f.tenant_id
        WHERE f.tenant_id=? AND f.document_id=?
        """,(r,n)->new File(r.getString("document_id"),r.getString("storage_key"),r.getString("original_name"),r.getLong("byte_size"),r.getString("media_type"),r.getString("sha256")),tenant,id).stream().findFirst().orElse(null);}
    public String create(String user,String tenant,String kb,Upload upload,Inspected file,Instant now){
        String doc=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();Timestamp time=Timestamp.from(now);
        jdbc.update("INSERT INTO documents (id,tenant_id,knowledge_base_id,title,source_type,active_file_hash,created_at) VALUES (?,?,?,?,'UPLOAD',?,?)",doc,tenant,kb,file.name(),file.sha256(),time);
        jdbc.update("INSERT INTO document_versions (id,tenant_id,document_id,version_no,content_text,sha256,index_state,created_at) VALUES (?,?,?,1,NULL,?,'NOT_INDEXED',?)",version,tenant,doc,file.sha256(),time);
        jdbc.update("INSERT INTO document_files VALUES (?,?,?,?,?,?,?,?,?)",doc,tenant,version,upload.key(),file.name(),file.size(),file.mime(),user,time);
        complete(upload.id(),doc);return doc;
    }
    public void complete(String upload,String doc){jdbc.update("UPDATE document_uploads SET status='COMPLETE',document_id=? WHERE id=? AND status='WRITING'",doc,upload);}
    public boolean writing(String id,Instant now){return jdbc.queryForObject("SELECT COUNT(*) FROM document_uploads WHERE id=? AND status='WRITING' AND expires_at>?",Integer.class,id,Timestamp.from(now))==1;}
    public void fail(Upload upload,Instant now){
        int changed=jdbc.update("UPDATE document_uploads SET status='FAILED' WHERE id=? AND status='WRITING'",upload.id());
        if(changed>0) cleanup(upload.key(),now);
    }
    public void cleanup(String key,Instant now){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM document_cleanup_jobs WHERE storage_key=?",Integer.class,key)==0)
            jdbc.update("INSERT INTO document_cleanup_jobs(storage_key,next_retry_at,created_at) VALUES (?,?,?)",key,Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),Timestamp.from(now));
    }
    public record Owned(String kb,boolean deleted) { }
    public Optional<Owned> owned(String tenant,String id){return jdbc.query("SELECT knowledge_base_id,deleted FROM documents WHERE tenant_id=? AND id=?",(r,n)->new Owned(r.getString(1),r.getBoolean(2)),tenant,id).stream().findFirst();}
    public void delete(String tenant,String id){jdbc.update("UPDATE documents SET deleted=TRUE,active_file_hash=NULL WHERE tenant_id=? AND id=?",tenant,id);}
    public List<String> expiredUsers(Instant now){return jdbc.query("SELECT DISTINCT user_id FROM document_uploads WHERE status='WRITING' AND expires_at<=? LIMIT 20",(r,n)->r.getString(1),Timestamp.from(now));}
    public List<Upload> expired(String user,Instant now){return jdbc.query("SELECT * FROM document_uploads WHERE user_id=? AND status='WRITING' AND expires_at<=?",(r,n)->new Upload(r.getString("id"),r.getString("storage_key"),r.getString("fingerprint"),r.getString("status"),null,r.getTimestamp("expires_at").toInstant()),user,Timestamp.from(now));}
    public List<String> cleanable(Instant now){return jdbc.query("SELECT storage_key FROM document_cleanup_jobs WHERE status='PENDING' AND attempts<8 AND next_retry_at<=? ORDER BY created_at LIMIT 20",(r,n)->r.getString(1),Timestamp.from(now));}
    public boolean lockCleanup(String key,Instant now){return !jdbc.queryForList("SELECT storage_key FROM document_cleanup_jobs WHERE storage_key=? AND status='PENDING' AND attempts<8 AND next_retry_at<=? FOR UPDATE",key,Timestamp.from(now)).isEmpty();}
    public void cleanupDone(String key){jdbc.update("UPDATE document_cleanup_jobs SET status='DONE',last_error_code=NULL WHERE storage_key=?",key);}
    public void cleanupFailed(String key,Instant retry){jdbc.update("UPDATE document_cleanup_jobs SET attempts=attempts+1,last_error_code='STORAGE_DELETE_FAILED',next_retry_at=? WHERE storage_key=?",Timestamp.from(retry),key);}
}
