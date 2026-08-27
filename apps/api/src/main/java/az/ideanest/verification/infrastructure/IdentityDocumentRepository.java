package az.ideanest.verification.infrastructure;

import az.ideanest.verification.domain.IdentityDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Documents, by the three questions asked of them — issue #105.
 *
 * <p>Every read is keyed on a verification or on an age. <strong>There is no read that
 * returns documents across people</strong>, and there must not be: the whole of the
 * "restricted access" in #105's title is that a document is reachable only from the
 * verification it belongs to, through a service that checks who is asking and records that
 * they did.
 */
public interface IdentityDocumentRepository extends JpaRepository<IdentityDocument, UUID> {

    /** What a reviewer sees listed. Metadata only until one is opened. */
    List<IdentityDocument> findByVerificationIdOrderByUploadedAtAsc(UUID verificationId);

    /** How many are already held, for the per-submission cap. */
    int countByVerificationId(UUID verificationId);

    /** One document, scoped to its verification so a wrong pairing cannot be opened. */
    Optional<IdentityDocument> findByIdAndVerificationId(UUID id, UUID verificationId);

    /**
     * The retention sweep — §17.4.
     *
     * <p>Deleting rather than flagging. A retention limit that marks a row instead of
     * removing it is not a retention limit, and the thing being removed here is a
     * photograph of somebody's passport.
     *
     * @return how many were destroyed
     */
    @Modifying
    @Query("delete from IdentityDocument document where document.verificationId in :verificationIds")
    int deleteForVerifications(List<UUID> verificationIds);

    /**
     * Documents older than a moment, whatever their verification decided.
     *
     * <p>The backstop. The ordinary path erases on a decision; this catches the submission
     * nobody ever looked at, which would otherwise sit in the table for the life of the
     * platform.
     */
    List<IdentityDocument> findByUploadedAtBefore(Instant moment);
}
