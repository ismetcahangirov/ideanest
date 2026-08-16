package az.ideanest.discovery.api;

import az.ideanest.discovery.application.AdminCollection;
import az.ideanest.discovery.application.CollectionDraft;
import az.ideanest.discovery.application.DiscoveryPage;
import az.ideanest.discovery.domain.CuratedCollection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the collection endpoints answer with. D-08.
 *
 * <p>Separate from the domain types for the reason {@link DiscoveryResponses} is: a
 * rename inside the module is not a breaking API change, and the wire format is
 * legible in one file.
 *
 * <p><strong>The public and the administrative shapes are two records, not one with
 * fields a curator sees and a reader does not.</strong> One record with a nullable
 * {@code publishedAt} would be one {@code if} away from telling anybody who asks which
 * collections the platform is preparing — see {@code CollectionNotFoundException} for
 * why that is confidential — and the {@code if} would live in a mapper rather than in
 * the type system.
 */
public final class CollectionResponses {

    private CollectionResponses() {
    }

    /**
     * One collection, as a reader sees it.
     *
     * @param kind {@code staff_selection}, {@code themed}, or {@code open_call}
     * @param title in the negotiated language, never absent
     * @param description absent when the collection has no standfirst
     * @param grantsBadge whether membership is an editorial badge (§3.2, §4.4)
     * @param projectCount publicly visible campaigns only; a suspended campaign a
     *     curator once chose is not counted
     * @param opensAt absent for a standing list
     * @param closesAt absent for one that does not expire
     */
    public record Collection(
            String id,
            String slug,
            String kind,
            String title,
            String description,
            DiscoveryResponses.Image image,
            boolean grantsBadge,
            long projectCount,
            Instant opensAt,
            Instant closesAt) {
    }

    /** {@code GET /v1/collections}. An object with one array, so it can grow a field. */
    public record CollectionIndex(List<Collection> items) {
    }

    /**
     * {@code GET /v1/collections/{slug}} — D-08's landing page.
     *
     * @param items the cards, in the curator's order, at most {@code limit} of them
     * @param nextCursor absent on the last page. <strong>A short page is not the end
     *     of the list</strong> — only the absence of this is
     */
    public record CollectionPage(
            Collection collection, List<DiscoveryResponses.Card> items, String nextCursor) {
    }

    /**
     * One collection as the curator who maintains it sees it.
     *
     * @param publishedAt absent when it has never been published or has been withdrawn
     * @param copy every locale it has a title for, keyed by code
     * @param projects in the curator's order, each saying whether the public sees it
     */
    public record AdminCollectionResponse(
            String id,
            String slug,
            String kind,
            Instant publishedAt,
            Instant opensAt,
            Instant closesAt,
            boolean grantsBadge,
            int sortOrder,
            DiscoveryResponses.Image image,
            Map<String, CopyBody> copy,
            List<AdminMember> projects) {
    }

    public record AdminCollectionIndex(List<AdminCollectionResponse> items) {
    }

    /** @param publiclyVisible false for a campaign in one of §6.1's seven hidden states */
    public record AdminMember(
            String projectId, String slug, String title, String state, int position, boolean publiclyVisible) {
    }

    /** A collection's copy in one language. */
    public record CopyBody(String title, String description) {
    }

    public static Collection collection(CuratedCollection collection) {
        return new Collection(
                collection.id().toString(),
                collection.slug(),
                collection.kind().wireValue(),
                collection.title(),
                collection.description(),
                collection.coverImage() == null
                        ? null
                        : new DiscoveryResponses.Image(
                                collection.coverImage().url(),
                                collection.coverImage().width(),
                                collection.coverImage().height()),
                collection.grantsBadge(),
                collection.projectCount(),
                collection.opensAt(),
                collection.closesAt());
    }

    public static CollectionIndex index(List<CuratedCollection> collections) {
        return new CollectionIndex(collections.stream().map(CollectionResponses::collection).toList());
    }

    public static CollectionPage page(CuratedCollection collection, DiscoveryPage projects) {
        return new CollectionPage(
                collection(collection),
                projects.items().stream().map(DiscoveryResponses::card).toList(),
                projects.nextCursor() == null ? null : projects.nextCursor().encode());
    }

    public static AdminCollectionResponse admin(AdminCollection collection) {
        Map<String, CopyBody> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CollectionDraft.Copy> entry : collection.copy().entrySet()) {
            copy.put(entry.getKey(), new CopyBody(entry.getValue().title(), entry.getValue().description()));
        }
        return new AdminCollectionResponse(
                collection.id().toString(),
                collection.slug(),
                collection.kind().wireValue(),
                collection.publishedAt(),
                collection.opensAt(),
                collection.closesAt(),
                collection.grantsBadge(),
                collection.sortOrder(),
                collection.coverImage() == null
                        ? null
                        : new DiscoveryResponses.Image(
                                collection.coverImage().url(),
                                collection.coverImage().width(),
                                collection.coverImage().height()),
                copy,
                collection.projects().stream()
                        .map(member -> new AdminMember(
                                member.projectId().toString(),
                                member.slug(),
                                member.title(),
                                member.state(),
                                member.position(),
                                member.publiclyVisible()))
                        .toList());
    }
}
