package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.CollectionKind;
import az.ideanest.discovery.domain.ProjectCard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A collection as the curator who maintains it sees it.
 *
 * <p><strong>Deliberately not {@code CuratedCollection}.</strong> That type describes
 * a list the public may already see and carries nothing about whether it is published,
 * precisely so that a public response cannot leak an editorial decision in progress.
 * This one carries the publication state, every translation rather than one resolved
 * name, and the membership — including campaigns the public read filters out, because
 * a curator who cannot see that the campaign they picked has been suspended cannot
 * replace it.
 *
 * @param publishedAt null when the collection has never been published or has been
 *     withdrawn. Which of the two, and who decided, is in {@code curation_events}
 * @param copy every locale the collection has a row for, keyed by code
 * @param projects in the curator's order, each saying whether the public can see it
 */
public record AdminCollection(
        UUID id,
        String slug,
        CollectionKind kind,
        Instant publishedAt,
        Instant opensAt,
        Instant closesAt,
        boolean grantsBadge,
        int sortOrder,
        ProjectCard.CoverImage coverImage,
        Map<String, CollectionDraft.Copy> copy,
        List<Member> projects) {

    /**
     * One curated campaign.
     *
     * @param position the curator's sparse order key (10, 20, 30), so that a client
     *     showing the list can reason about where an insertion would go
     * @param publiclyVisible false for a campaign in one of §6.1's seven hidden
     *     states. <strong>The curator is told; the public read simply omits it.</strong>
     *     A collection whose first three cards silently vanished is a page nobody can
     *     debug from the outside
     */
    public record Member(
            UUID projectId, String slug, String title, String state, int position, boolean publiclyVisible) {
    }
}
