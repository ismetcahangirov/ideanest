package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.RefreshToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * The lookup every refresh performs. Returns used and expired tokens too:
     * the caller has to be able to tell "already rotated" from "never existed",
     * because only the first one means a copy is in circulation.
     */
    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /** The family, for inspection after a reuse detection. */
    List<RefreshToken> findBySessionIdOrderByIssuedAtAsc(UUID sessionId);
}
