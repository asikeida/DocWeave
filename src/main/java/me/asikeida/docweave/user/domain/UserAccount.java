package me.asikeida.docweave.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 全局登录身份；组织角色在后续成员模块中维护，不保存在账号上。 */
@Entity
@Table(name = "users")
public class UserAccount {
    public enum Status { ACTIVE, DISABLED }

    @Id
    @Column(length = 36)
    private String id;
    @Column(nullable = false, unique = true, length = 64)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Status status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserAccount() { }

    public UserAccount(String username, String passwordHash, String displayName, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = Status.ACTIVE;
        this.createdAt = now;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isActive() { return status == Status.ACTIVE; }
}
