package az.ideanest.signature.infrastructure;

import az.ideanest.signature.domain.SignatureProviderName;
import az.ideanest.signature.domain.SignatureRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * V67's signatures, by the two questions asked of them — #428.
 *
 * <p><strong>{@link #findByProviderAndProviderSessionId} is what makes a resolve idempotent.</strong>
 * A session resolves to the same answer every time it is asked, so a retry has to land on the row
 * that already exists rather than writing a second signature for one act of signing. V67's unique
 * index is what guarantees it; this is how the caller checks before relying on the guarantee.
 *
 * <p>{@link #findBySignerUserIdOrderBySignedAtDesc} is the console's read and #429's. Unpaged: an
 * account signs the creator agreement once per version, and a creator with enough signatures to
 * need a cursor is a finding rather than a screen.
 */
public interface SignatureRecordRepository extends JpaRepository<SignatureRecord, UUID> {

    Optional<SignatureRecord> findByProviderAndProviderSessionId(
            SignatureProviderName provider, String providerSessionId);

    List<SignatureRecord> findBySignerUserIdOrderBySignedAtDesc(UUID signerUserId);
}
