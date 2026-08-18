package az.ideanest.analytics.application;

import az.ideanest.analytics.domain.ReferralSource;
import java.util.UUID;

/**
 * A visit, as the client describes it.
 *
 * @param projectId which campaign was visited. The touch is per campaign, not per
 *     platform: a visitor brought to one campaign by a tweet and to another by a
 *     newsletter has done two things, and one visitor-wide "last source" would credit
 *     whichever was more recent for both
 * @param visitorToken the token this client is already holding, or null the first
 *     time. Never an account identifier and never anything derived from one — see
 *     {@code VisitorToken}
 * @param source where the visit came from, already normalised and bounded by
 *     {@link ReferralSource}
 * @param accountId the caller, when they happen to be signed in. That is the whole
 *     difference between a visit that can be attributed immediately and one that is
 *     waiting to be claimed
 */
public record CaptureReferralVisit(UUID projectId, String visitorToken, ReferralSource source, UUID accountId) {
}
