ALTER TABLE personal_sample_initializations ADD COLUMN import_state VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (import_state IN ('PENDING','IMPORTED_NOT_INDEXED'));
ALTER TABLE personal_sample_initializations ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE personal_sample_initializations ADD COLUMN last_error_code VARCHAR(64);
ALTER TABLE personal_sample_initializations ADD COLUMN next_retry_at TIMESTAMP(6);
CREATE TABLE knowledge_bases (
 id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, workspace_id VARCHAR(36) NOT NULL,
 name VARCHAR(80) NOT NULL, seed_version VARCHAR(16) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_kb_workspace FOREIGN KEY (tenant_id,workspace_id) REFERENCES workspaces(tenant_id,id),
 CONSTRAINT uk_kb_tenant UNIQUE (tenant_id,id), CONSTRAINT uk_kb_seed UNIQUE (tenant_id,seed_version)
);
CREATE TABLE documents (
 id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, knowledge_base_id VARCHAR(36) NOT NULL,
 title VARCHAR(160) NOT NULL, sample_key VARCHAR(64) NOT NULL, seed_version VARCHAR(16) NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_document_kb FOREIGN KEY (tenant_id,knowledge_base_id) REFERENCES knowledge_bases(tenant_id,id),
 CONSTRAINT uk_document_tenant UNIQUE (tenant_id,id),
 CONSTRAINT uk_document_seed UNIQUE (tenant_id,knowledge_base_id,seed_version,sample_key)
);
CREATE TABLE document_versions (
 id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 version_no INT NOT NULL, content_text TEXT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 index_state VARCHAR(24) NOT NULL CHECK (index_state='NOT_INDEXED'), created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_version_document FOREIGN KEY (tenant_id,document_id) REFERENCES documents(tenant_id,id),
 CONSTRAINT uk_document_version UNIQUE (tenant_id,document_id,version_no)
);
