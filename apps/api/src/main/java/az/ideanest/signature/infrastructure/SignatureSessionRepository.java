package az.ideanest.signature.infrastructure;

import az.ideanest.signature.domain.SignatureProviderName;
import az.ideanest.signature.domain.SignatureSessionRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Sessions in flight (#429). */
public interface SignatureSessionRepository extends JpaRepository<SignatureSessionRecord, UUID> {

    /**
     * The session a caller named, scoped to the caller.
     *
     * <p>The signer is part of the query rather than checked afterwards. A find-then-compare
     * is the same thing until somebody adds a second call site and forgets the compare.
     */
    Optional<SignatureSessionRecord> findByProviderAndProviderSessionIdAndSignerUserId(
            SignatureProviderName provider, String providerSessionId, UUID signerUserId);
}
