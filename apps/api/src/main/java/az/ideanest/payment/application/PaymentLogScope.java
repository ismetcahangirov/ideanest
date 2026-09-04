package az.ideanest.payment.application;

import az.ideanest.payment.domain.TransactionStatus;
import java.util.Locale;
import java.util.UUID;

/**
 * Which slice of the payment log is being read — AD-05, #304.
 *
 * <p><strong>A pledge, a campaign, an outcome, or nothing.</strong> V41 gives
 * {@code transactions} an index on {@code (pledge_id, created_at DESC)} and one on
 * {@code (project_id, created_at DESC)}; V64 adds {@code (status, created_at DESC, id DESC)} and
 * {@code (created_at DESC, id DESC)}, replacing the {@code (status, id DESC)} V63 built for an
 * ordering #412 removed.
 * A filter outside that set is a sequential scan over what §22.1 expects to become the largest
 * table the platform holds, and the person who runs it first is a moderator with a support
 * ticket open.
 *
 * <h2>The outcome filter, and why it was not here before</h2>
 *
 * <p>This record used to say, at length, that there was no filter on status because "every
 * failed charge on the platform" is a real question with no index behind it, and that adding
 * the parameter first would mean shipping the scan and discovering it in production. That
 * reasoning was sound and its conclusion was wrong by the time anybody used the screen: #404
 * observed that the log's own description promises it includes rejected calls
 * ("rədd edilənlər də daxil olmaqla"), that failed provider calls are the main reason to open
 * it at all, and that they were the one view it could not select. The answer is the index, and
 * V63 is it — the filter and the index it needs land in one change.
 *
 * <p><strong>The outcome combines with the other two rather than replacing them.</strong> It is
 * the one filter here that does: "the failures on this campaign" is a question with an index
 * behind it either way — the campaign's own, with the status as a filter step over the handful
 * of rows one campaign produces — and refusing the combination would mean answering "why did
 * this collection run leave money behind" by reading pages.
 *
 * <p>What is still deliberately absent, each a one-line migration on the day it is needed:
 *
 * <ul>
 *   <li><strong>No filter on provider or type.</strong> Only {@code CHARGE} is written today,
 *       so a type filter selects everything or nothing, and nobody has yet asked a question
 *       about one provider that the campaign filter did not answer.
 *   <li><strong>No date range.</strong> The log is ordered by {@code created_at} since #412, so
 *       paging back <em>is</em> going back in time and a reader who wants last Tuesday can page
 *       to it. That was the argument before, made about the identifier on the belief that the
 *       key and the timestamp said the same thing; #412 is what that belief cost, and the
 *       conclusion survives it because the order is now the column itself. The audit trail has
 *       a range because that surface is asked "what happened last Tuesday" directly, and
 *       {@code AuditEntryRepository} says why a bound there is a parameter rather than another
 *       query. The day this screen is asked the same way, V64's two indexes both lead on
 *       {@code created_at} and a bound on it is a narrower scan of a range already chosen.
 * </ul>
 *
 * @param pledgeId one pledge's whole attempt history — every decline and the collection that
 *     eventually succeeded — or null
 * @param projectId everything that moved on one campaign, or null. Ignored when a pledge is
 *     named: the pledge is the narrower question and the two indexes do not combine
 * @param status {@code SUCCEEDED}, {@code FAILED} or {@code PENDING}, or null for every
 *     outcome. Spelled as a string rather than as {@link TransactionStatus} because the admin
 *     module constructs this and may not name a payment {@code domain} type — the boundary
 *     {@code ModuleBoundaryTests} enforces, and the same reason {@code PaymentLogResponses}
 *     passes the outcome back out as a string. {@link #outcome()} is where it becomes the enum
 */
public record PaymentLogScope(UUID pledgeId, UUID projectId, String status) {

    /** Every call the platform has made, newest first. */
    public static final PaymentLogScope EVERYTHING = new PaymentLogScope(null, null, null);

    /**
     * The scope this actually is, with the combination no index serves resolved and the
     * outcome in the spelling the column uses.
     *
     * <p>Trimmed and upper-cased rather than refused for case, because a query parameter is
     * typed by whoever is holding the URL and {@code ?status=failed} means one thing. A value
     * that is not an outcome at all is a different matter — see {@link #outcome()}.
     */
    public PaymentLogScope normalised() {
        String outcome = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);

        return pledgeId != null
                ? new PaymentLogScope(pledgeId, null, outcome)
                : new PaymentLogScope(null, projectId, outcome);
    }

    /**
     * The outcome as the column spells it, or null for every outcome.
     *
     * <p><strong>An unrecognised value is refused rather than dropped.</strong> Dropping it
     * would answer a wider question than the one asked and say so nowhere a reader would look:
     * a screen that asked for failures and was handed every call would draw successes under a
     * chip that says "rejected". {@link #normalised()} first, so that the refusal is about the
     * value and not about its case.
     *
     * @throws UnknownTransactionOutcomeException when the value is not one of the three
     */
    public TransactionStatus outcome() {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TransactionStatus.valueOf(status);
        } catch (IllegalArgumentException notAnOutcome) {
            throw new UnknownTransactionOutcomeException(status);
        }
    }
}
