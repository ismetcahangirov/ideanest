package az.ideanest.project.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A campaign could not be submitted because the creator's plan does not stretch to it.
 *
 * <p><strong>Deliberately not {@link SubscriptionRequiredException}, and the web client
 * treats the two differently.</strong> That one means "you have not paid", and the answer
 * is the pricing page. This one means "you have paid, and this does not fit" -- and the
 * answer may equally be to withdraw a campaign, or to lower a goal, neither of which
 * involves buying anything. Redirecting somebody who is already a customer to a price list
 * reads as the platform trying to sell them something instead of answering them.
 *
 * <p>The limit and the plan are on the exception because the message has to name them. "You
 * have reached your limit" tells a creator nothing they can act on; "Starter allows one
 * campaign at a time and you have one live" tells them both what to do about it and what
 * changing plan would buy.
 *
 * @param limit which bound was hit
 */
public class PlanLimitExceededException extends RuntimeException {

    /** Which of a plan's bounds refused this submission. */
    public enum Limit {

        /** Too many campaigns already in the platform's hands. */
        ACTIVE_CAMPAIGNS,

        /** The funding goal is above what this plan is sold as covering. */
        GOAL_CEILING
    }

    private final UUID projectId;
    private final Limit limit;
    private final String planCode;

    /** The plan's bound, as text: a count for one limit and an amount for the other. */
    private final String allowed;

    /** What the campaign or the account actually presents against it, in the same shape. */
    private final String actual;

    private PlanLimitExceededException(
            UUID projectId, Limit limit, String planCode, String allowed, String actual, String message) {
        super(message);
        this.projectId = projectId;
        this.limit = limit;
        this.planCode = planCode;
        this.allowed = allowed;
        this.actual = actual;
    }

    public static PlanLimitExceededException tooManyCampaigns(
            UUID projectId, String planCode, int allowed, long held) {

        return new PlanLimitExceededException(
                projectId,
                Limit.ACTIVE_CAMPAIGNS,
                planCode,
                Integer.toString(allowed),
                Long.toString(held),
                "Plan " + planCode + " allows " + allowed + " campaigns at once; this account holds " + held);
    }

    public static PlanLimitExceededException goalTooLarge(
            UUID projectId, String planCode, BigDecimal ceiling, BigDecimal goal, String currency) {

        return new PlanLimitExceededException(
                projectId,
                Limit.GOAL_CEILING,
                planCode,
                ceiling.toPlainString(),
                goal.toPlainString(),
                "Plan " + planCode + " covers goals up to " + ceiling.toPlainString() + " " + currency
                        + "; this campaign asks for " + goal.toPlainString());
    }

    public UUID projectId() {
        return projectId;
    }

    public Limit limit() {
        return limit;
    }

    public String planCode() {
        return planCode;
    }

    public String allowed() {
        return allowed;
    }

    public String actual() {
        return actual;
    }
}
