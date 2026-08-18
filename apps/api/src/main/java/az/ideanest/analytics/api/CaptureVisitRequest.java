package az.ideanest.analytics.api;

import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.analytics.domain.ReferralSource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Somebody arrived, and this is where from."
 *
 * <p>The channel is the only required field, and the rest of the rule — a direct visit
 * names nothing, everything else names something — is {@link ReferralSource}'s, not
 * repeated here as annotations. Two statements of the same rule drift apart, and the
 * one in the value object is the one the schema mirrors.
 *
 * @param visitorToken the token this client was already holding, or null the first
 *     time. Bounded so that an unbounded string cannot be sent to an endpoint that
 *     takes no credential; an unrecognisable value is replaced rather than refused,
 *     for the reason {@code ReferralVisitService} gives
 * @param channel what kind of place this visit came from
 * @param source the place: {@code twitter}, {@code newsletter}. Folded and bounded by
 *     the value object
 * @param campaign which push, when the link named one
 * @param referrerCode the creator's own share link, when the visit came through one.
 *     The same 64 characters {@code pledges.referrer_code} allows, because it is the
 *     same code
 */
public record CaptureVisitRequest(
        @Size(max = 128, message = "A visitor token may not exceed 128 characters") String visitorToken,
        @NotNull(message = "A visit says what kind of place it came from") ReferralChannel channel,
        @Size(max = 64, message = "A source may not exceed 64 characters") String source,
        @Size(max = 64, message = "A campaign may not exceed 64 characters") String campaign,
        @Size(max = 64, message = "A referrer code may not exceed 64 characters") String referrerCode) {

    /**
     * The source this request describes.
     *
     * <p>Built here rather than in the service so that a malformed one is a 400 from
     * the module's advice instead of a 500 from the middle of a transaction: the value
     * object throws {@link IllegalArgumentException}, and
     * {@code ReferralExceptionHandler} turns exactly this call's failures into
     * {@code REFERRAL_SOURCE_INVALID}.
     */
    public ReferralSource toSource() {
        return ReferralSource.of(channel, source, campaign, referrerCode);
    }
}
