package az.ideanest.analytics.application;

import java.time.Instant;

/**
 * What a client is told after recording a visit.
 *
 * <p>Two values and deliberately nothing else. It does not say whether a row was
 * written — a repeat visit inside the session interval is deduplicated, and telling
 * the client which of its requests counted would be telling it how to make one count.
 * It does not echo the source back, because a client that needs to be told what it
 * just sent has a different bug.
 *
 * @param visitorToken the token to keep and send next time: the one that was
 *     presented, or a newly minted one. The only moment this value exists on the
 *     server is the request that produced it
 * @param expiresAt when this visit stops being evidence. Returned so that a client
 *     can expire its own copy of the token at the same moment the server stops caring
 *     about it, rather than holding an identifier for browsing that can no longer be
 *     attributed to anything
 */
public record ReferralVisit(String visitorToken, Instant expiresAt) {
}
