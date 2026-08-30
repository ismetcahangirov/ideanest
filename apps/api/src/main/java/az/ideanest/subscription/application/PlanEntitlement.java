package az.ideanest.subscription.application;

import az.ideanest.shared.access.PublishingAllowance;
import az.ideanest.shared.access.PublishingEntitlement;
import az.ideanest.subscription.domain.Subscription;
import az.ideanest.subscription.domain.SubscriptionPlan;
import az.ideanest.subscription.infrastructure.SubscriptionPlanRepository;
import az.ideanest.subscription.infrastructure.SubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an account may publish, answered for the module that has to refuse.
 *
 * <p><strong>The whole of this module's public surface to the rest of the platform.</strong>
 * The project module names {@code shared.access.PublishingEntitlement} and gets a
 * {@link PublishingAllowance}; it never names a plan, a subscription, or a table here.
 * {@code ModuleBoundaryTests} checks that.
 *
 * <p><strong>Two queries rather than a join, and rather than a cache.</strong> The
 * subscription is read by account and the plan by identifier, and both are single-row
 * index hits on a path taken once per campaign submission — which is a few times a day on
 * this platform, not a few times a second. A join would save one round trip; a cache would
 * save both and would hand an entitlement to somebody whose subscription lapsed while it
 * was warm, which is the failure this method is here to prevent.
 *
 * <p><strong>The limits come from the plan as it is now, not as it was sold.</strong> That
 * is V62's asymmetry and the reason this reads the plan at all rather than taking
 * everything from the subscription row: raising a limit is a gift to everybody currently
 * on the plan, which is what an operator raising a limit means. The price is the half that
 * is snapshotted, and nothing here reads it.
 *
 * <p><strong>It fails closed, in every direction.</strong> No subscription, one waiting
 * for payment, one whose period ran out, or one whose plan has somehow gone — all of them
 * are {@link PublishingAllowance#NONE}. A platform that cannot tell whether somebody paid
 * refuses to publish rather than publishing for everybody.
 */
@Service
public class PlanEntitlement implements PublishingEntitlement {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final Clock clock;

    public PlanEntitlement(SubscriptionRepository subscriptions, SubscriptionPlanRepository plans, Clock clock) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PublishingAllowance allowanceOf(UUID accountId) {
        if (accountId == null) {
            return PublishingAllowance.NONE;
        }

        Instant now = clock.instant();

        Optional<Subscription> held = subscriptions.openFor(accountId);
        if (held.isEmpty() || !held.get().entitlesAt(now)) {
            // Covers all three of "never subscribed", "waiting for payment" and "lapsed".
            // The caller does not need them apart: none of the three may publish, and the
            // difference is a sentence the pricing page draws from the subscription
            // itself, which it reads separately.
            return PublishingAllowance.NONE;
        }

        Optional<SubscriptionPlan> plan = plans.findById(held.get().getPlanId());
        if (plan.isEmpty()) {
            // V62's foreign key restricts, so this is unreachable short of somebody
            // deleting a plan by hand. Answered as "no entitlement" rather than thrown,
            // because the caller is a campaign submission and an exception there would be
            // a 500 on a creator's screen for an operator's mistake.
            return PublishingAllowance.NONE;
        }

        SubscriptionPlan current = plan.get();
        return new PublishingAllowance(
                true,
                current.getCode(),
                current.getMaxActiveCampaigns(),
                current.getGoalCeiling(),
                current.getGoalCeiling() == null ? null : current.getPrice().currency());
    }
}
