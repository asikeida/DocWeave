package me.asikeida.docweave.auth.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import me.asikeida.docweave.auth.domain.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LoginSession s where s.id = :id")
    Optional<LoginSession> lockById(String id);
}
