/**
 * What a creator pays to publish, and what that buys them.
 *
 * <p><strong>Its own module because three others need it and none should own it.</strong>
 * The project module refuses a submission without an entitlement; the console edits the
 * catalogue; the pricing page reads it. Putting the plans in {@code project} would mean
 * the console reached into the campaign module to change a price, and putting them in
 * {@code fee} would conflate two different things the platform charges for — §9's cut of
 * a pledge is taken from a backer's money and belongs to the campaign's terms, whereas
 * this is a creator's own bill.
 *
 * <p>Callers name {@code shared.access.PublishingEntitlement} and get a
 * {@code PublishingAllowance}. Nothing outside this module names {@code SubscriptionPlan}
 * or {@code Subscription}, or reads {@code subscription_plans} and {@code subscriptions}.
 *
 * <p><strong>No money moves here.</strong> §9.2 ships no payment provider adapter while
 * #60 is unanswered, so a paid plan is bought into {@code PENDING_PAYMENT} and a member of
 * staff records that the transfer arrived. V62's header argues why that is how a platform
 * with no processor sells rather than a stub pretending to be one, and what changes when
 * a provider exists: step two, and nothing above it.
 */
package az.ideanest.subscription;
