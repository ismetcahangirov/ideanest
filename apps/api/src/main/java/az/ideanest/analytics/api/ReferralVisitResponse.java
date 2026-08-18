package az.ideanest.analytics.api;

import az.ideanest.analytics.application.ReferralVisit;
import java.time.Instant;

/**
 * What the client keeps: the token, and when it stops being worth keeping.
 *
 * @param visitorToken send this back on the next visit. It is not a credential and
 *     authenticates nothing — it names a bucket of visits, which is why losing it
 *     costs an attribution rather than an account
 * @param expiresAt when this visit stops being evidence, so a client can drop its copy
 *     at the same moment the server stops being able to use it
 */
public record ReferralVisitResponse(String visitorToken, Instant expiresAt) {

    public static ReferralVisitResponse of(ReferralVisit visit) {
        return new ReferralVisitResponse(visit.visitorToken(), visit.expiresAt());
    }
}
