ALTER TABLE document_versions MODIFY COLUMN content_text MEDIUMTEXT NULL;
ALTER TABLE document_versions ADD COLUMN content_sha256 VARCHAR(64) NULL;
ALTER TABLE document_versions ADD COLUMN parser_version VARCHAR(32) NULL;
ALTER TABLE document_versions ADD COLUMN parsed_at TIMESTAMP(6) NULL;
ALTER TABLE document_versions ADD CONSTRAINT uk_version_document_owner UNIQUE (tenant_id,document_id,id);
CREATE TABLE document_processing_jobs (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL, version_id VARCHAR(36) NOT NULL,
 parser_version VARCHAR(32) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count>=0 AND attempt_count<=5),
 claim_token VARCHAR(36), lease_expires_at TIMESTAMP(6), next_attempt_at TIMESTAMP(6),
 last_error_code VARCHAR(64), created_at TIMESTAMP(6) NOT NULL, started_at TIMESTAMP(6),
 finished_at TIMESTAMP(6), updated_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uk_processing_version UNIQUE (tenant_id,version_id,parser_version),
 CONSTRAINT fk_processing_version FOREIGN KEY (tenant_id,document_id,version_id)
 REFERENCES document_versions(tenant_id,document_id,id)
);
CREATE INDEX ix_processing_pending ON document_processing_jobs(status,next_attempt_at);
CREATE INDEX ix_processing_expired ON document_processing_jobs(status,lease_expires_at);
CREATE TABLE document_text_locations (
 tenant_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL, version_id VARCHAR(36) NOT NULL,
 sequence_no INT NOT NULL CHECK (sequence_no>=0), page_number INT, start_line INT, end_line INT,
 start_offset INT NOT NULL CHECK (start_offset>=0), end_offset INT NOT NULL,
 CONSTRAINT pk_text_location PRIMARY KEY (tenant_id,version_id,sequence_no),
 CONSTRAINT ck_text_offsets CHECK (end_offset>=start_offset),
 CONSTRAINT ck_text_position CHECK (
  (page_number IS NOT NULL AND page_number>=1 AND start_line IS NULL AND end_line IS NULL)
  OR (page_number IS NULL AND start_line IS NOT NULL AND end_line IS NOT NULL AND start_line>=1 AND end_line>=start_line)),
 CONSTRAINT fk_text_location_version FOREIGN KEY (tenant_id,document_id,version_id)
 REFERENCES document_versions(tenant_id,document_id,id)
);
