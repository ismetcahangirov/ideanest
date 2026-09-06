package az.ideanest.shared.legal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Which agreement governs, whether an account has accepted it, and the recording of one —
 * asked from outside the module that holds the rows. Issues #425, #426, #427.
 *
 * <p><strong>The whole of the legal module's surface to the rest of the platform.</strong>
 * The project module refuses a submission without an acceptance; the pledge module refuses
 * a confirmation without one. Neither may name {@code legal.domain} or
 * {@code legal.infrastructure} — {@code ModuleBoundaryTests} — so the contract lives here
 * and {@code legal.application.LegalAgreements} implements it.
 *
 * <p><strong>Why this port writes, where {@link az.ideanest.shared.access.PublishingEntitlement}
 * only reads.</strong> An acceptance has to be written in the same transaction as the thing
 * it is a precondition for: a pledge confirmed without its acknowledgement recorded, or an
 * acknowledgement recorded against a confirmation that then failed, are both rows that say
 * something untrue about what a person did. {@code Outbox} is {@code MANDATORY} for exactly
 * this reason and this follows it — {@link #accept} must be called inside the caller's
 * transaction.
 *
 * <h2>It fails <em>open</em>, and that is the opposite of the subscription gate</h2>
 *
 * <p>{@link #inForce} answers {@link Optional#empty()} when no version of that agreement has
 * been published, and both gates then let the action through. That is deliberate and it is
 * the reverse of {@code PublishingEntitlement}, which answers "no entitlement" to everything
 * when the subscription module is absent.
 *
 * <p>The two cases are not alike. A subscription gate that failed open would give the
 * product away, and the creator meeting it can fix it in a minute by paying. A legal gate
 * that failed closed would refuse every campaign and every pledge on the platform, with a
 * message telling people to accept a document that does not exist — an unactionable
 * refusal, and the deployment it would happen in is the one where the seeding of #439 has
 * not run yet. So the rule is: <strong>a document that exists must be accepted; a document
 * that does not exist is not a requirement.</strong> The day the text is published the gate
 * bites, for everybody, without a deployment.
 *
 * <p>Which also means the absence is visible rather than silent: {@code /v1/legal/documents}
 * lists what is published, and a platform with no creator agreement is a platform whose
 * list is short.
 */
public interface Agreements {

    /**
     * The version of this agreement that governs at this instant, if any.
     *
     * <p>Evaluated against the clock on every call. A version published to take effect next
     * month is not an answer here until next month, which is the entire reason
     * {@code effective_from} is a column rather than the publication time.
     *
     * @return empty when nothing of this kind has been published, or when everything
     *     published is not yet effective — see the class comment on why the callers treat
     *     that as "no requirement" rather than "refuse everything"
     */
    Optional<AgreementInForce> inForce(AgreementKind kind);

    /**
     * Whether this account has already accepted that exact version.
     *
     * <p>Takes the version rather than the kind, so that a caller which has already resolved
     * {@link #inForce} does not resolve it twice and cannot ask about a different version
     * from the one it is about to name in its refusal.
     *
     * @param accountId the authenticated caller, taken from the access token's subject
     * @param agreement what {@link #inForce} returned
     */
    boolean hasAccepted(UUID accountId, AgreementInForce agreement);

    /**
     * Records that this account accepted that version, now.
     *
     * <p><strong>Idempotent.</strong> V65's {@code document_acceptances_one_per_user_version}
     * means a second acceptance of the same version is the first one, not a second row and
     * not a failure: a client that retried a confirmation must not be told it has agreed
     * twice, and an acceptance is not a thing that can happen twice.
     *
     * <p><strong>Must be called inside the caller's transaction.</strong> See the class
     * comment. The address and the user agent are taken from the current request rather than
     * passed, following {@code AuditEnvironment}: an actor who could name their own source
     * address would be writing the alibi as well as the record.
     */
    void accept(UUID accountId, AgreementInForce agreement);

    /**
     * Whether that acceptance carries a national e-signature rather than a tick — issue #429.
     *
     * <p>A separate question from {@link #hasAccepted}, and it has to be, because the answer
     * differs for the same row: an acceptance is an acceptance whether or not a signature is
     * filed against it, and only the submission gate cares which. Folding the two into one
     * boolean would make every caller that merely wants to know whether somebody agreed start
     * failing on the day #424 turns the requirement on.
     *
     * <p>False when nothing has been accepted at all, so a caller may ask this alone when a
     * signature is what it requires.
     */
    boolean hasSigned(UUID accountId, AgreementInForce agreement);

    /**
     * Whether a campaign with this goal, submitted by this creator, must be signed rather than
     * ticked — issue #429's rule, and #424's number.
     *
     * <p>Asked here rather than assembled by the caller. The rule has two inputs — the goal and
     * the creator's legal subject — and the second is a compliance fact the project module has
     * no business reading. The gate asks a question; the legal module, which holds the
     * configuration #424 will one day fill in, answers it.
     *
     * <p><strong>The goal and not the amount collected.</strong> #424's first point, on §9.6's
     * reasoning: success "is decided at the deadline from confirmed pledges, and never
     * revisited", and a requirement that could fail a campaign after backers were told it
     * succeeded would be worse than no requirement.
     *
     * <p>False for everybody until {@code ideanest.legal.signature.enabled} is turned on, which
     * is the honest state of the platform until #424 answers.
     *
     * @param goalAmount the campaign's funding goal, or null if it has none yet
     */
    boolean signatureRequiredOf(UUID accountId, BigDecimal goalAmount);
}
