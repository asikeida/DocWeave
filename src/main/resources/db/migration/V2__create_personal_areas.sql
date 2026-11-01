CREATE TABLE organizations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_user_id VARCHAR(36) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_personal_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT uk_personal_owner_pair UNIQUE (id, owner_user_id)
);
CREATE TABLE organization_memberships (
    tenant_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role = 'OWNER'),
    active BOOLEAN NOT NULL,
    CONSTRAINT fk_personal_member_owner FOREIGN KEY (tenant_id, user_id) REFERENCES organizations(id, owner_user_id),
    CONSTRAINT uk_personal_member_pair UNIQUE (tenant_id, user_id)
);
CREATE TABLE workspaces (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    CONSTRAINT fk_personal_workspace_org FOREIGN KEY (tenant_id) REFERENCES organizations(id),
    CONSTRAINT uk_personal_workspace_pair UNIQUE (tenant_id, id)
);
CREATE TABLE workspace_grants (
    tenant_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role = 'ADMIN'),
    PRIMARY KEY (tenant_id, workspace_id, user_id),
    CONSTRAINT fk_personal_grant_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces(tenant_id, id),
    CONSTRAINT fk_personal_grant_member FOREIGN KEY (tenant_id, user_id) REFERENCES organization_memberships(tenant_id, user_id)
);
CREATE TABLE personal_sample_initializations (
    tenant_id VARCHAR(36) NOT NULL PRIMARY KEY,
    seed_version VARCHAR(16) NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state = 'WAITING_FEATURE'),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_personal_seed_org FOREIGN KEY (tenant_id) REFERENCES organizations(id)
);
CREATE TABLE organization_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    actor_user_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'PERSONAL_AREA_CREATED'),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_personal_event_member FOREIGN KEY (tenant_id, actor_user_id) REFERENCES organization_memberships(tenant_id, user_id)
);
