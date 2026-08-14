package az.ideanest.auth.infrastructure;

import az.ideanest.auth.domain.IdentityProvider;
import az.ideanest.auth.domain.ProviderIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderIdentityRepository extends JpaRepository<ProviderIdentity, UUID> {

    /**
     * The lookup every social sign-in starts with, and the only one that
     * authenticates: a verified {@code (provider, subject)} pair is the account,
     * whatever the address on it says today.
     */
    Optional<ProviderIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    /** Whether this person already signed in with this provider under another subject. */
    Optional<ProviderIdentity> findByUserIdAndProvider(UUID userId, IdentityProvider provider);
}
