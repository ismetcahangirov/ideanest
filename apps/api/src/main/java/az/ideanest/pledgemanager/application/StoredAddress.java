package az.ideanest.pledgemanager.application;

import az.ideanest.pledgemanager.domain.PostalAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored address, opened.
 *
 * <p>The envelope's metadata beside its contents: which pledge it belongs to, whether
 * the creator has frozen it (PM-08), and when it last changed. The key label is
 * deliberately <strong>not</strong> here — it is an operational detail of V36's
 * rotation scheme and means nothing to either a backer or a creator, and a field that
 * names the encryption key is one a response might one day carry by accident.
 *
 * @param lockedAt null while the backer may still edit
 * @param updatedAt what a creator reads to find out whether an address changed after
 *     they printed a label
 */
public record StoredAddress(UUID pledgeId, PostalAddress address, Instant lockedAt, Instant updatedAt) {

    public boolean isLocked() {
        return lockedAt != null;
    }
}
