package az.ideanest.discovery.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One curated list, as a reader sees it. D-08's landing page header, and one row of
 * the collections index.
 *
 * <p>Deliberately not the row. Nothing here says whether the collection is published
 * or when: a value of this type only ever describes a collection the public may
 * already see, because the queries that build it apply the visibility predicate
 * before they project anything. There is therefore no way to render one of these and
 * accidentally reveal a list somebody is still assembling.
 *
 * @param id the collection
 * @param slug its handle, and its half of {@code /collections/{slug}}
 * @param kind staff selection, theme, or open call
 * @param title resolved against {@code Accept-Language} through the taxonomy's chain
 *     — the requested locale, then {@code az}, then the slug. Never null, for the
 *     reason {@code Taxonomy.resolveName} is never null: a heading that renders empty
 *     is worse than one that renders a handle
 * @param description null when the collection has no standfirst in any language the
 *     chain reached. Absent rather than empty on the wire
 * @param coverImage null while the collection has none
 * @param opensAt when the window began, or null for a standing list. Carried because
 *     an open call's dates are the most important thing on its page
 * @param closesAt when the window ends, or null. Always in the future for a
 *     collection the public can see, because a closed one is not returned at all
 * @param grantsBadge whether being in this list badges a campaign (§3.2, §4.4, D-05).
 *     On the wire so that a client can say "featured in" rather than "part of"
 * @param projectCount how many <strong>publicly visible</strong> campaigns are in it.
 *     Not the number of membership rows: a curator may have added a campaign that was
 *     later suspended, and the row stays while the campaign stops being counted
 */
public record CuratedCollection(
        UUID id,
        String slug,
        CollectionKind kind,
        String title,
        String description,
        ProjectCard.CoverImage coverImage,
        Instant opensAt,
        Instant closesAt,
        boolean grantsBadge,
        long projectCount) {
}
