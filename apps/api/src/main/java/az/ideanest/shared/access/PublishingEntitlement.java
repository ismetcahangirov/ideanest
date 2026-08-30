package az.ideanest.shared.access;

import java.util.UUID;

/**
 * Whether an account has bought the right to publish, asked from outside the module that
 * knows.
 *
 * <p><strong>A third kind of authority, beside the two already here.</strong>
 * {@link ProjectAuthorisation} answers "what has this campaign's creator granted this
 * account"; {@link PlatformStaff} answers "does this account work for the platform"; this
 * answers "has this account paid to publish". They are genuinely different questions —
 * one is delegated by a user, one is conferred by the operator, and this one is bought —
 * and a creator holding every capability on their own campaign still cannot submit it
 * without an entitlement.
 *
 * <p><strong>Why the interface is here and the implementation is not.</strong>
 * {@code PlatformStaff}'s argument, unchanged: the caller is the project module, which
 * may not name {@code subscription.domain} or {@code subscription.infrastructure}, and
 * {@code shared} may not depend on a module. So the contract lives here and
 * {@code subscription.application.PlanEntitlement} implements it.
 *
 * <p><strong>It returns an allowance rather than a verdict, and that is the load-bearing
 * decision.</strong> {@link PublishingAllowance} has the argument: the subscription module
 * knows what a plan permits and does not know how many campaigns the account is holding,
 * because those rows are the project module's. A {@code mayPublish(accountId, projectId)}
 * would have to count them, which is a query over {@code projects} in a module that owns
 * none.
 *
 * <p><strong>It fails closed.</strong> An account with no subscription, an expired one,
 * or one still waiting for its payment to be recorded gets {@link
 * PublishingAllowance#NONE} — and so does every account in a deployment where nothing
 * implements this. A platform that cannot tell whether somebody paid refuses to publish
 * rather than publishing for everybody.
 */
public interface PublishingEntitlement {

    /**
     * What this account may publish right now.
     *
     * <p>Evaluated against the clock on every call rather than cached, because an
     * entitlement ends at an instant: a subscription that lapsed a minute ago is one this
     * has to stop returning, and the alternative is a creator publishing on a plan they
     * are no longer paying for until something evicts a cache.
     *
     * @param accountId the authenticated caller, taken from the access token's subject and
     *     never from the request
     * @return never null. {@link PublishingAllowance#NONE} when the account holds nothing
     */
    PublishingAllowance allowanceOf(UUID accountId);
}
