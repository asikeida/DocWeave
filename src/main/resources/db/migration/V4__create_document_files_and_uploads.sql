ALTER TABLE documents MODIFY COLUMN sample_key VARCHAR(64) NULL;
ALTER TABLE documents MODIFY COLUMN seed_version VARCHAR(16) NULL;
ALTER TABLE documents ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'SAMPLE';
ALTER TABLE documents ADD COLUMN active_file_hash VARCHAR(64) NULL;
ALTER TABLE documents ADD CONSTRAINT ck_document_source CHECK (
 (source_type='SAMPLE' AND sample_key IS NOT NULL AND seed_version IS NOT NULL AND active_file_hash IS NULL)
 OR (source_type='UPLOAD' AND sample_key IS NULL AND seed_version IS NULL));
ALTER TABLE documents ADD CONSTRAINT uk_active_upload_hash UNIQUE (tenant_id,knowledge_base_id,active_file_hash);
ALTER TABLE document_versions MODIFY COLUMN content_text TEXT NULL;
ALTER TABLE document_versions ADD CONSTRAINT uk_version_tenant_id UNIQUE (tenant_id,id);
CREATE TABLE document_files (
 document_id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, storage_key VARCHAR(36) NOT NULL UNIQUE,
 original_name VARCHAR(160) NOT NULL, byte_size BIGINT NOT NULL CHECK (byte_size>0),
 media_type VARCHAR(64) NOT NULL, created_by VARCHAR(36) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_file_document FOREIGN KEY (tenant_id,document_id) REFERENCES documents(tenant_id,id),
 CONSTRAINT fk_file_version FOREIGN KEY (tenant_id,version_id) REFERENCES document_versions(tenant_id,id),
 CONSTRAINT fk_file_user FOREIGN KEY (created_by) REFERENCES users(id)
);
CREATE TABLE document_uploads (
 id VARCHAR(36) NOT NULL PRIMARY KEY, user_id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL,
 knowledge_base_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(80) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, storage_key VARCHAR(36) NOT NULL UNIQUE,
 original_name VARCHAR(160) NOT NULL, byte_size BIGINT NOT NULL CHECK (byte_size>0),
 media_type VARCHAR(64) NOT NULL, sha256 VARCHAR(64) NOT NULL, document_id VARCHAR(36),
 status VARCHAR(16) NOT NULL CHECK (status IN ('WRITING','COMPLETE','FAILED')),
 expires_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uk_upload_idempotency UNIQUE (user_id,idempotency_key),
 CONSTRAINT fk_upload_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_upload_kb FOREIGN KEY (tenant_id,knowledge_base_id) REFERENCES knowledge_bases(tenant_id,id)
);
CREATE INDEX ix_upload_expiration ON document_uploads(status,expires_at);
CREATE TABLE document_cleanup_jobs (
 storage_key VARCHAR(36) NOT NULL PRIMARY KEY, attempts INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(6) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE')),
 last_error_code VARCHAR(64), created_at TIMESTAMP(6) NOT NULL
);
