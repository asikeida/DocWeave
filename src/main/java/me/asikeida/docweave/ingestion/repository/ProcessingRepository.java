package me.asikeida.docweave.ingestion.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import me.asikeida.docweave.ingestion.parser.ParsedDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久解析状态；调用者按账号→文档→任务加锁，任何发布都由 claim token 和有效租约限制。 */
@Repository
public class ProcessingRepository {
    public static final String PARSER_VERSION = "text-v1";
    private final JdbcTemplate jdbc;

    public ProcessingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Target(String tenant, String document, String version, String kb, String source,
                         String key, long bytes, String mime, String sha256) {
        @Override public String toString() { return "ProcessingTarget[document=" + document + "]"; }
    }
    public record Job(String id, String tenant, String document, String version, String user, String status,
                      int attempts, String token, Instant lease, Instant nextAttempt, String error) { }
    public record Claim(Job job, Target target) { }

    public void lockUser(String user) { jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", user); }
    public boolean activeUser(String user) { return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND status='ACTIVE'", Integer.class, user) == 1; }
    public void lockDocument(String tenant, String doc) { jdbc.queryForList("SELECT id FROM documents WHERE tenant_id=? AND id=? FOR UPDATE", tenant, doc); }
    public void lockAuthorization(String tenant, String workspace, String user) {
        jdbc.queryForList("SELECT id FROM organizations WHERE id=? FOR UPDATE", tenant);
        jdbc.queryForList("SELECT user_id FROM organization_memberships WHERE tenant_id=? AND user_id=? FOR UPDATE", tenant, user);
        jdbc.queryForList("SELECT id FROM workspaces WHERE tenant_id=? AND id=? FOR UPDATE", tenant, workspace);
        jdbc.queryForList("SELECT user_id FROM workspace_grants WHERE tenant_id=? AND workspace_id=? AND user_id=? FOR UPDATE", tenant, workspace, user);
    }
    public void lockContainer(String tenant, String document) {
        jdbc.queryForList("SELECT k.id FROM knowledge_bases k JOIN documents d ON d.tenant_id=k.tenant_id AND d.knowledge_base_id=k.id WHERE d.tenant_id=? AND d.id=? FOR UPDATE", tenant, document);
    }

    public Optional<Target> target(String tenant, String workspace, String doc) {
        return jdbc.query("""
                SELECT d.id,d.tenant_id,d.knowledge_base_id,d.source_type,v.id AS version_id,v.sha256,
                       f.storage_key,f.byte_size,f.media_type
                FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id AND k.tenant_id=d.tenant_id
                JOIN document_versions v ON v.tenant_id=d.tenant_id AND v.document_id=d.id AND v.version_no=1
                LEFT JOIN document_files f ON f.tenant_id=d.tenant_id AND f.document_id=d.id AND f.version_id=v.id
                WHERE d.tenant_id=? AND k.workspace_id=? AND d.id=? AND d.deleted=FALSE AND k.deleted=FALSE
                """, (r, n) -> new Target(r.getString("tenant_id"), r.getString("id"), r.getString("version_id"),
                r.getString("knowledge_base_id"), r.getString("source_type"), r.getString("storage_key"),
                r.getLong("byte_size"), r.getString("media_type"), r.getString("sha256")), tenant, workspace, doc).stream().findFirst();
    }

    public Optional<Job> jobFor(String tenant, String version) {
        return query("j.tenant_id=? AND j.version_id=? AND j.parser_version=?", tenant, version, PARSER_VERSION).stream().findFirst();
    }
    public Optional<Job> lockJob(String tenant, String id) {
        jdbc.queryForList("SELECT id FROM document_processing_jobs WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id);
        return query("j.tenant_id=? AND j.id=? AND j.parser_version=?", tenant, id, PARSER_VERSION).stream().findFirst();
    }
    public List<Job> candidates(Instant now) {
        return query("j.parser_version=? AND ((j.status='PENDING' AND j.next_attempt_at<=?) OR (j.status='RUNNING' AND j.lease_expires_at<=?)) ORDER BY j.created_at,j.id LIMIT 20",
                PARSER_VERSION, time(now), time(now));
    }
    private List<Job> query(String where, Object... args) {
        return jdbc.query("SELECT j.*,o.owner_user_id FROM document_processing_jobs j JOIN organizations o ON o.id=j.tenant_id WHERE " + where,
                (r, n) -> job(r), args);
    }
    private static Job job(ResultSet r) throws SQLException {
        return new Job(r.getString("id"), r.getString("tenant_id"), r.getString("document_id"), r.getString("version_id"),
                r.getString("owner_user_id"), r.getString("status"), r.getInt("attempt_count"), r.getString("claim_token"),
                instant(r.getTimestamp("lease_expires_at")), instant(r.getTimestamp("next_attempt_at")), r.getString("last_error_code"));
    }

    public int pending(String user) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM document_processing_jobs j JOIN organizations o ON o.id=j.tenant_id
                WHERE o.owner_user_id=? AND j.status IN ('PENDING','RUNNING')
                """, Integer.class, user);
    }
    public void insert(Target target, Instant now) {
        jdbc.update("""
                INSERT INTO document_processing_jobs
                (id,tenant_id,document_id,version_id,parser_version,status,next_attempt_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'PENDING',?,?,?)
                """, UUID.randomUUID().toString(), target.tenant(), target.document(), target.version(), PARSER_VERSION,
                time(now), time(now), time(now));
    }
    public void claim(Job job, String token, Instant now, Instant lease) {
        jdbc.update("""
                UPDATE document_processing_jobs SET status='RUNNING',attempt_count=attempt_count+1,claim_token=?,
                lease_expires_at=?,started_at=?,finished_at=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status='PENDING'
                """, token, time(lease), time(now), time(now), job.tenant(), job.id());
    }
    public boolean renew(Job job, Instant now, Instant lease) {
        return jdbc.update("""
                UPDATE document_processing_jobs SET lease_expires_at=?,updated_at=?
                WHERE tenant_id=? AND id=? AND status='RUNNING' AND claim_token=? AND lease_expires_at>?
                """, time(lease), time(now), job.tenant(), job.id(), job.token(), time(now)) == 1;
    }
    public boolean fenced(Job expected, Job current, Instant now) {
        return current.status().equals("RUNNING") && Objects.equals(expected.token(), current.token())
                && current.lease() != null && current.lease().isAfter(now);
    }
    public void finish(Job job, String status, String error, Instant now, Instant next) {
        jdbc.update("""
                UPDATE document_processing_jobs SET status=?,last_error_code=?,next_attempt_at=?,claim_token=NULL,
                lease_expires_at=NULL,finished_at=?,updated_at=? WHERE tenant_id=? AND id=?
                """, status, error, next == null ? null : time(next), next == null ? time(now) : null,
                time(now), job.tenant(), job.id());
    }
    public void cancel(String tenant, String document, Instant now) {
        jdbc.update("""
                UPDATE document_processing_jobs SET status='CANCELLED',last_error_code='RESOURCE_UNAVAILABLE',
                claim_token=NULL,lease_expires_at=NULL,next_attempt_at=NULL,finished_at=?,updated_at=?
                WHERE tenant_id=? AND document_id=? AND status IN ('PENDING','RUNNING')
                """, time(now), time(now), tenant, document);
    }
    public void publish(Job job, ParsedDocument parsed, String digest, Instant now) {
        jdbc.update("""
                UPDATE document_versions SET content_text=?,content_sha256=?,parser_version=?,parsed_at=?
                WHERE tenant_id=? AND document_id=? AND id=?
                """, parsed.text(), digest, PARSER_VERSION, time(now), job.tenant(), job.document(), job.version());
        jdbc.update("DELETE FROM document_text_locations WHERE tenant_id=? AND version_id=?", job.tenant(), job.version());
        for (var location : parsed.locations()) {
            jdbc.update("""
                    INSERT INTO document_text_locations
                    (tenant_id,document_id,version_id,sequence_no,page_number,start_line,end_line,start_offset,end_offset)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, job.tenant(), job.document(), job.version(), location.sequence(), location.page(), location.startLine(),
                    location.endLine(), location.start(), location.end());
        }
        finish(job, "SUCCEEDED", null, now, null);
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp time(Instant value) { return Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)); }
}
