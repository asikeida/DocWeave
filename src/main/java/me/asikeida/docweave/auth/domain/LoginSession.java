package me.asikeida.docweave.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 可撤销的一次登录；到期时间固定，不随刷新无限延长。 */
@Entity
@Table(name = "auth_sessions")
public class LoginSession {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean revoked;

    protected LoginSession() { }

    public LoginSession(String userId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive(Instant now) { return !revoked && now.isBefore(expiresAt); }
    public void revoke() { revoked = true; }
}
