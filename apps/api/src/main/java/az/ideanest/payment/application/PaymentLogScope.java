package az.ideanest.payment.application;

import java.util.UUID;

/**
 * Which slice of the payment log is being read — AD-05, #304.
 *
 * <p><strong>Three shapes, because V41 gives {@code transactions} three useful orders and
 * no more.</strong> There is an index on {@code (pledge_id, created_at DESC)}, one on
 * {@code (project_id, created_at DESC)}, and the primary key. A filter outside that set is
 * a sequential scan over what §22.1 expects to become the largest table the platform holds,
 * and the person who runs it first is a moderator with a support ticket open.
 *
 * <p>What is deliberately absent, and would each be a one-line migration on the day it is
 * needed rather than a scan shipped today:
 *
 * <ul>
 *   <li><strong>No filter on status.</strong> "Every failed charge on the platform" is a
 *       real question and it has no index. The screen shows the status of every row it
 *       draws, and the answer to "why did this card fail" is the pledge filter — which is
 *       the question §9.6's retry schedule is actually argued from.
 *   <li><strong>No filter on provider or type.</strong> Same reason, and neither is a
 *       question anybody has asked yet.
 *   <li><strong>No date range.</strong> The identifier carries the millisecond (§7.3), so
 *       the order is the date and paging back <em>is</em> going back in time.
 * </ul>
 *
 * @param pledgeId one pledge's whole attempt history — every decline and the collection
 *     that eventually succeeded — or null
 * @param projectId everything that moved on one campaign, or null. Ignored when a pledge is
 *     named: the pledge is the narrower question and the two indexes do not combine
 */
public record PaymentLogScope(UUID pledgeId, UUID projectId) {

    /** Every call the platform has made, newest first. */
    public static final PaymentLogScope EVERYTHING = new PaymentLogScope(null, null);

    /** The scope this actually is, with the combination no index serves resolved. */
    public PaymentLogScope normalised() {
        return pledgeId != null ? new PaymentLogScope(pledgeId, null) : this;
    }
}
