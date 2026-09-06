package az.ideanest.shared.compliance;

import java.util.Optional;
import java.util.UUID;

/**
 * The question "who is this creator, legally" — issue #430, asked across a module boundary.
 *
 * <p>{@code Agreements}' shape, for the same reason. The project module freezes a subject at
 * submission and the payout module reads one to decide withholding; neither may name
 * {@code compliance.domain.CreatorLegalSubject}, so both name this and
 * {@code compliance.application.CreatorLegalSubjects} answers it.
 *
 * <h2>Two reads, and why they are not one</h2>
 *
 * <p>{@link #of(UUID)} is the live fact — what the creator has entered and may correct this
 * afternoon. {@link #frozenFor(UUID)} is what was true when a campaign was submitted, which
 * is a different question with a different answer and is the one a payout must ask.
 *
 * <p>The precedent is {@code payouts.creator_id}, denormalised from the campaign because "a
 * campaign's creator is a mutable fact and the payout was calculated for the person who held
 * it at the time". A legal subject is mutable in exactly the same way and for exactly the
 * same reason must be snapshotted: a creator who submits as an individual and edits the row
 * to a company three months later has not retroactively submitted as a company, and a payout
 * that read the live row would withhold as though they had.
 */
public interface LegalSubjects {

    /** What the creator has recorded about themselves, if anything. */
    Optional<LegalSubject> of(UUID accountId);

    /**
     * What the creator had recorded when this campaign was submitted.
     *
     * <p>Empty for a campaign submitted before the creator recorded anything, which is every
     * campaign submitted before #430 shipped and every campaign submitted while the subject
     * is not yet required. A caller that treats empty as a refusal would fail campaigns that
     * were correct under the rule in force when they were submitted — §5.6's snapshot
     * argument, and #424's third point.
     */
    Optional<LegalSubject> frozenFor(UUID projectId);

    /**
     * Freeze the creator's current subject onto a campaign, at submission.
     *
     * <p>Idempotent, and idempotent in the direction that matters: a second submission of the
     * same campaign — a resubmission after a rejection — writes the subject that is true now,
     * because that submission is the one being decided. A campaign that is never resubmitted
     * keeps the subject it was submitted with forever.
     *
     * <p>Records nothing when the creator has no subject. That is the correct behaviour until
     * #424 sets a threshold: an unfrozen campaign is one the rule did not apply to.
     */
    void freezeFor(UUID projectId, UUID accountId);
}
