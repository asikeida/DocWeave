package me.asikeida.docweave.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 只保存刷新凭据的 SHA-256；保留已消费记录以检测重放。 */
@Entity
@Table(name = "auth_refresh_tokens")
public class RefreshCredential {
    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;
    @Column(nullable = false)
    private boolean consumed;

    protected RefreshCredential() { }

    public RefreshCredential(String tokenHash, String sessionId) {
        this.tokenHash = tokenHash;
        this.sessionId = sessionId;
    }

    public String getSessionId() { return sessionId; }
    public boolean isConsumed() { return consumed; }
    public void consume() { consumed = true; }
}
