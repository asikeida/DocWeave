package me.asikeida.docweave.auth.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import me.asikeida.docweave.auth.domain.RefreshCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefreshCredentialRepository extends JpaRepository<RefreshCredential, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshCredential r where r.tokenHash = :hash")
    Optional<RefreshCredential> lockByHash(String hash);
}
