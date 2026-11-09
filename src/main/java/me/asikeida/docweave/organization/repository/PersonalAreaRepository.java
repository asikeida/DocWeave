package me.asikeida.docweave.organization.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import me.asikeida.docweave.organization.dto.PersonalArea;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 个人聚合的显式 SQL：复合外键与所有读取使用同一组织/成员/授权边界。 */
@Repository
public class PersonalAreaRepository {
    private final JdbcTemplate jdbc;
    public PersonalAreaRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockActiveUser(String userId) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", userId).isEmpty();
    }

    public boolean existsFor(String userId) {
        return !jdbc.queryForList("SELECT id FROM organizations WHERE owner_user_id=?", userId).isEmpty();
    }

    public Optional<PersonalArea> findAuthorized(String userId) {
        return jdbc.query("""
                SELECT o.id, w.id AS workspace_id, w.name, s.state
                FROM organizations o
                JOIN organization_memberships m ON m.tenant_id=o.id AND m.user_id=o.owner_user_id
                JOIN workspaces w ON w.tenant_id=o.id
                JOIN workspace_grants g ON g.tenant_id=o.id AND g.workspace_id=w.id AND g.user_id=m.user_id AND g.role='ADMIN'
                JOIN personal_sample_initializations s ON s.tenant_id=o.id
                WHERE o.owner_user_id=? AND o.active=TRUE AND m.active=TRUE
                """, (rs, row) -> new PersonalArea(rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"), rs.getString("state")), userId)
                .stream().findFirst();
    }

    public void create(String userId, Instant now) {
        String tenant = UUID.randomUUID().toString();
        String workspace = UUID.randomUUID().toString();
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("INSERT INTO organizations VALUES (?,?,'个人组织',TRUE,?)", tenant, userId, timestamp);
        jdbc.update("INSERT INTO organization_memberships VALUES (?,?,'OWNER',TRUE)", tenant, userId);
        jdbc.update("INSERT INTO workspaces VALUES (?,?,'我的资料')", workspace, tenant);
        jdbc.update("INSERT INTO workspace_grants VALUES (?,?,?,'ADMIN')", tenant, workspace, userId);
        jdbc.update("INSERT INTO personal_sample_initializations VALUES (?,'v1','WAITING_FEATURE',?)", tenant, timestamp);
        jdbc.update("INSERT INTO organization_events VALUES (?,?,?,'PERSONAL_AREA_CREATED',?)", UUID.randomUUID().toString(), tenant, userId, timestamp);
    }
}
