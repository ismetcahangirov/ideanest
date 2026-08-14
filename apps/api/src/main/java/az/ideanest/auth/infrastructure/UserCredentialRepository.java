package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.UserCredential;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Password credentials, keyed by the user they belong to. */
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
}
