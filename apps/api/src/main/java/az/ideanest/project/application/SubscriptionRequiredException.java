package az.ideanest.project.application;

import java.util.UUID;

/**
 * A campaign could not be submitted because its creator has not subscribed.
 *
 * <p><strong>The gate on publishing.</strong> Building a campaign is free and always will
 * be -- a draft is private and costs the platform nothing -- but submitting one for review
 * spends a moderator's afternoon and, if it is cleared, puts the platform's name behind it.
 * That is the line, and this is the refusal at it.
 *
 * <p><strong>It is the one refusal the web client answers with a redirect.</strong>
 * Everything else the editor raises is fixed on the screen the creator is already looking
 * at; this one cannot be, because what is missing is not on the campaign. So
 * {@code ReviewPanel} navigates to the pricing page rather than rendering an alert with a
 * link in it -- a link would be the same decision offered twice.
 *
 * <p>Covers all three of "never subscribed", "waiting for a payment to be recorded" and
 * "the period ran out". The three are not distinguished here on purpose: none of them may
 * publish, and the pricing page reads the subscription itself to say which one the creator
 * is in.
 */
public class SubscriptionRequiredException extends RuntimeException {

    private final UUID projectId;

    public SubscriptionRequiredException(UUID projectId) {
        super("Campaign " + projectId + " cannot be submitted without an active subscription");
        this.projectId = projectId;
    }

    public UUID projectId() {
        return projectId;
    }
}
