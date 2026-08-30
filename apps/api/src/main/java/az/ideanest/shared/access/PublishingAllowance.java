package az.ideanest.shared.access;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What an account is currently allowed to publish, as the answer crosses a module
 * boundary.
 *
 * <p><strong>It says what is allowed, not whether a particular campaign may go.</strong>
 * That distinction is the whole reason this is a record and not a method returning a
 * verdict. The subscription module knows an account holds Growth and that Growth permits
 * three campaigns at once; it does not know — and has no business knowing — how many
 * campaigns that account is holding, because those rows belong to the project module.
 * Asking the subscription module for the verdict would put a query over {@code projects}
 * in a module that owns none, which is exactly what {@code ModuleBoundaryTests} exists to
 * stop.
 *
 * <p>So the allowance travels and the caller decides. {@code ProjectTransitionService}
 * counts its own campaigns, compares its own goal, and produces its own refusal.
 *
 * <p><strong>{@code null} means "no limit", in both fields.</strong> Not zero, and not
 * {@link Integer#MAX_VALUE}. A sentinel would put the unlimited case into the same
 * arithmetic as the limited one, and the comparison that forgot to special-case it would
 * refuse a creator for holding more than nothing. {@link #permitsAnother} and
 * {@link #permitsGoal} are here so that no caller has to remember which convention this
 * is — there are two call sites today and the third would get it wrong.
 *
 * @param subscribed whether there is an active subscription behind this at all. False
 *     means the account may not publish, whatever the other fields say — they are then
 *     null, and reading them would tell a caller "no limits" when the truth is "no
 *     permission"
 * @param planCode which plan, for the refusal's message and for the log. Null exactly
 *     when {@code subscribed} is false
 * @param maxActiveCampaigns how many campaigns this account may have in the platform's
 *     hands at once, or null for no limit
 * @param goalCeiling the largest funding goal a campaign may be submitted with under this
 *     plan, or null for no ceiling. Denominated in {@link #currency}
 * @param currency what {@link #goalCeiling} is in, so that a comparison against a
 *     campaign's goal is never made between two currencies. Null exactly when the ceiling
 *     is
 */
public record PublishingAllowance(
        boolean subscribed,
        String planCode,
        Integer maxActiveCampaigns,
        BigDecimal goalCeiling,
        String currency) {

    /**
     * The answer for an account with nothing.
     *
     * <p>Also the answer a deployment gets when the subscription module is not there at
     * all — see {@code PublishingEntitlement} on why that direction is the safe one.
     */
    public static final PublishingAllowance NONE = new PublishingAllowance(false, null, null, null, null);

    public PublishingAllowance {
        if (!subscribed && (planCode != null || maxActiveCampaigns != null || goalCeiling != null)) {
            // An unsubscribed allowance carrying limits would read as "no limits" to
            // anything that checked the numbers before checking the flag, which is the
            // one mistake this type is shaped to prevent.
            throw new IllegalArgumentException("An allowance with no subscription carries no plan and no limits");
        }
        if (subscribed) {
            Objects.requireNonNull(planCode, "A subscribed allowance names its plan");
        }
        if (goalCeiling == null ^ currency == null) {
            // A ceiling with no currency is a number nothing may be compared against, and
            // a currency with no ceiling is a field nobody reads.
            throw new IllegalArgumentException("A goal ceiling and its currency are present together");
        }
    }

    /**
     * Whether one more campaign fits.
     *
     * @param currentlyHeld how many the account already has in the platform's hands,
     *     counted by whoever owns those rows
     */
    public boolean permitsAnother(long currentlyHeld) {
        if (!subscribed) {
            return false;
        }
        return maxActiveCampaigns == null || currentlyHeld < maxActiveCampaigns;
    }

    /**
     * Whether a campaign with this goal may be submitted.
     *
     * <p>A null goal passes. That is not this rule being lenient: a campaign with no goal
     * is refused by §5.3's checklist a line later, with a message that sends the creator
     * to the field they left empty rather than to the pricing page.
     *
     * @param goal the campaign's funding goal, or null if it has none yet
     * @param goalCurrency what that goal is denominated in. A goal in a currency this
     *     ceiling is not in is <em>allowed</em> — see the body
     */
    public boolean permitsGoal(BigDecimal goal, String goalCurrency) {
        if (!subscribed) {
            return false;
        }
        if (goal == null || goalCeiling == null) {
            return true;
        }
        if (!currency.equals(goalCurrency)) {
            // §21.2 makes a conversion rate an approximation shown to a user and never the
            // basis of a decision, so there is no honest way to compare 100,000 AZN against
            // a ceiling written in euro. Allowing it is the deliberate direction: the
            // platform is single-currency today (`ProjectEditingService.SUPPORTED_CURRENCY`),
            // so this branch is unreachable in practice, and if a second currency ever
            // arrives the failure is a ceiling that does not bite rather than a creator
            // refused by arithmetic nobody can defend.
            return true;
        }
        return goal.compareTo(goalCeiling) <= 0;
    }
}
