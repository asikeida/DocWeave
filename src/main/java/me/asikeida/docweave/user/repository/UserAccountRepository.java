package me.asikeida.docweave.user.repository;

import java.util.Optional;
import me.asikeida.docweave.user.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByUsername(String username);
}
