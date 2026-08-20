package az.ideanest.pledge.application;

import java.time.Instant;
import java.util.UUID;

/**
 * §4.7's CD-10: a filter with a name, saved against a campaign.
 *
 * <p><strong>It belongs to the campaign, not to the person who saved it.</strong> Every
 * holder of {@code VIEW_FINANCES} on the campaign reads, replaces and deletes its
 * segments, because a segment is the team's working vocabulary — "our German backers",
 * "the early-bird tier" — and a private bookmark would mean a collaborator sending a bulk
 * message to a segment nobody else can see. {@link #createdBy()} is for the support
 * conversation that starts "who set this up", and for nothing else.
 *
 * @param filter what it selects, re-evaluated on every read. V31's header says why no
 *     membership list is stored
 * @param updatedAt when the filter behind the name last changed. A segment whose
 *     definition moved under a saved report is the one thing a creator would want to see
 *     dated
 */
public record BackerSegment(
        UUID id, UUID projectId, String name, BackerFilter filter, UUID createdBy, Instant createdAt, Instant updatedAt) {
}
