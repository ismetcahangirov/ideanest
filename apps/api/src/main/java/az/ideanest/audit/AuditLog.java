package az.ideanest.audit;

import az.ideanest.shared.observability.Redaction;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AD-14 (#107): how a privileged action becomes a record that outlives everybody
 * who could have wanted it not to.
 *
 * <h2>The audit write is part of the caller's transaction. Deliberately</h2>
 *
 * <p>{@link #record} is {@code MANDATORY}, which is the same decision
 * {@code shared.outbox.Outbox} makes and for a sharper reason. <strong>"The action
 * succeeded but nobody recorded it" is the failure mode this issue exists to
 * prevent</strong>, and there is exactly one way to remove it: stop having two
 * commits. The row is written by the transaction that approved the campaign,
 * withdrew the grant, or removed the second factor, so the two are visible together
 * or neither is. There is no window, because there is no second commit to have a
 * window around.
 *
 * <p>Three alternatives, and why each is worse:
 *
 * <ul>
 *   <li><strong>{@code REQUIRED}.</strong> It would quietly open a transaction for a
 *       caller that had none and commit the row on its own — which is the divergence
 *       above, arrived at by the convenience of not having to say so. A caller with
 *       no transaction has nothing for the record to be atomic <em>with</em>, and
 *       the honest answer to that is an exception at the call site, in a test.
 *   <li><strong>{@code REQUIRES_NEW} everywhere.</strong> Then a record survives a
 *       business change that rolled back, and the table asserts that a campaign was
 *       approved when it was not. An audit trail with false entries is worse than
 *       one with gaps: a gap is visible, and a false entry is evidence.
 *   <li><strong>An after-commit listener.</strong> Same window as publishing after a
 *       commit, and {@code ProjectTransitionService} already refuses it for the
 *       state transitions in exactly these words: "an audit row that can fail
 *       independently of the thing it audits is an audit trail with gaps in exactly
 *       the circumstances that produce incidents".
 * </ul>
 *
 * <p><strong>What happens if the write fails.</strong> It throws, and the caller's
 * transaction goes with it — so the privileged action does not happen either. That
 * is the intended answer and not a regrettable side effect. The alternative is
 * swallowing the failure, and a catch block here would mean the platform performing
 * privileged actions it has decided it cannot record: the exact state #107 exists
 * to make impossible, reached quietly, on the one afternoon the disk was full.
 *
 * <h2>The second entry point, and what it costs</h2>
 *
 * <p>{@link #recordIndependently} is {@code REQUIRES_NEW}. It is for the two cases
 * where there is no transaction to be atomic with, and for nothing else:
 *
 * <ul>
 *   <li><strong>A refusal.</strong> A caller recording {@link AuditOutcome#REFUSED}
 *       is about to throw, and a row written in its transaction would be rolled back
 *       by the very refusal it describes. "Somebody tried to switch off this second
 *       factor and failed" would then be unrecordable by construction.
 *   <li><strong>An action that is not a database write.</strong>
 *       {@code GET /v1/me/export} copies everything the platform holds about a
 *       person into one response and changes nothing. There is no commit to join.
 * </ul>
 *
 * <p>It is honest about the weakness: the record commits before the caller finishes,
 * so a failure afterwards leaves a row describing something that did not go through.
 * For a refusal that is harmless — the refusal is what the row says. For an export
 * it is the safe direction on purpose: a record of an export that the client never
 * received is an over-record, and an export with no record is the gap. It also takes
 * a second connection from a pool of ten while holding the first, which is why it is
 * named rather than convenient.
 *
 * <h2>Redaction</h2>
 *
 * <p>{@code detail} and the user agent go through §17.4's {@link Redaction} before
 * they are stored, then are truncated to {@link AuditProperties}'s bounds. An audit
 * table is not a licence to retain personal data, and it is the worst possible place
 * to acquire some by accident: nothing may edit a row to take it out again.
 *
 * <p>The source address is the one thing stored as it arrived, because "from where"
 * is half of what makes the row worth having. V21's header has that argument in
 * full, including what it means for §17.4's anonymisation.
 */
@Component
public class AuditLog {

    private final AuditEntryRepository entries;
    private final AuditProperties properties;

    public AuditLog(AuditEntryRepository entries, AuditProperties properties) {
        this.entries = entries;
        this.properties = properties;
    }

    /**
     * Records something that happened, in the transaction that made it happen.
     *
     * @param action what was done, which also decides what kind of thing it was
     *     done to
     * @param entityId which one
     * @param actor who did it — {@link AuditActor#user}, {@link AuditActor#moderator}
     *     or {@link AuditActor#system}, and never a bare identifier, so that the
     *     actor and the subject cannot be swapped
     * @param outcome how it came out
     * @return the row's identifier, so that a caller can log the two together and an
     *     incident can be traced from a business row to the record of the decision
     * @throws org.springframework.transaction.IllegalTransactionStateException when
     *     there is no transaction to join. That is the class of bug this propagation
     *     exists to surface: a privileged action performed outside a transaction has
     *     nothing its record can be atomic with
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(AuditAction action, UUID entityId, AuditActor actor, AuditOutcome outcome) {
        return write(action, entityId, actor, outcome, null);
    }

    /**
     * The same, with whatever the columns cannot say: which capabilities a grant
     * carried, which states a transition ran between, why a refusal was one.
     *
     * <p>Redacted and truncated before it is stored. Say what happened rather than
     * copying what it happened to — a note, a body, or an address duplicated into
     * here is personal data with a different retention rule from the row it came
     * from, and this is the table with no retention rule at all yet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(AuditAction action, UUID entityId, AuditActor actor, AuditOutcome outcome, String detail) {
        return write(action, entityId, actor, outcome, detail);
    }

    /**
     * Records in a transaction of its own, committing whatever the caller then does.
     *
     * <p><strong>For a refusal, and for a privileged action that is not a database
     * write.</strong> Anything else belongs in {@link #record}: a change and its
     * record that commit separately can disagree, and the point of this package is
     * that they cannot.
     *
     * <p>Called from another bean, always — a self-invocation would not pass through
     * the proxy and the propagation would be silently ignored, which
     * {@code SessionRevoker} makes the same note about for the same reason.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordIndependently(
            AuditAction action, UUID entityId, AuditActor actor, AuditOutcome outcome, String detail) {

        return write(action, entityId, actor, outcome, detail);
    }

    /**
     * Flushed rather than merely queued, for {@code Outbox}'s reason: a row the
     * database refuses — a malformed address, an over-long action — fails here, at
     * the line that recorded it, instead of at a commit whose stack trace names
     * nothing the author wrote. The row is still invisible to everybody else until
     * that commit; the flush changes when the statement is sent, not when it becomes
     * true.
     */
    private UUID write(
            AuditAction action, UUID entityId, AuditActor actor, AuditOutcome outcome, String detail) {

        AuditEnvironment environment = AuditEnvironment.current();
        AuditEntry entry = AuditEntry.record(
                action,
                entityId,
                actor,
                outcome,
                new AuditEnvironment(
                        environment.sourceAddress(),
                        clean(environment.userAgent(), properties.userAgentMaxLength()),
                        environment.requestId(),
                        environment.traceId()),
                clean(detail, properties.detailMaxLength()));

        return entries.saveAndFlush(entry).getId();
    }

    /**
     * Redact, then truncate. In that order, and it matters: truncating first can cut
     * a value in half so that neither rule matches the remainder — half an address
     * is still somebody's address — and it can leave a mask that no longer says it
     * is one.
     */
    private static String clean(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String redacted = Redaction.redact(text);
        return redacted.length() <= maxLength ? redacted : redacted.substring(0, maxLength);
    }
}
