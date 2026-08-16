package az.ideanest.discovery.api;

import az.ideanest.discovery.application.CurationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Curating: the admin half of #48. §4.11's AD-03.
 *
 * <h2>Why this exists here rather than in epic #100</h2>
 *
 * <p>§10.2's {@code # Administration} block does not list curation endpoints, and the
 * honest reading of that is that nobody had written them down yet: §4.11 names Curation
 * as an admin module — "editorial badges, collections, open calls, placement" — and D-08
 * asks for the landing pages that render what this writes. Shipping the schema and the
 * public reads without a way in would mean a migration creating four tables that nothing
 * can write to, a {@code showOnly=featured} filter that can never match anything, and a
 * feature that is only reachable by hand-editing production. An unreachable feature is
 * worse than an absent one.
 *
 * <p>So this is the minimum coherent set: see the lists, create one, describe it,
 * publish or withdraw it, and add, remove, and reorder the campaigns in it. Deliberately
 * absent, and named in the pull request rather than left to be discovered:
 *
 * <ul>
 *   <li><strong>No delete.</strong> A collection that anything has happened to cannot be
 *       hard deleted — {@code curation_events.collection_id} has no {@code ON DELETE}
 *       clause, on purpose — and withdrawing one is unpublishing it, which 404s to the
 *       public and leaves the record of why intact.
 *   <li><strong>No endpoint over the audit trail.</strong> §4.11's AD-14 is a
 *       platform-wide immutable log of privileged actions and epic #100 owns it; a
 *       curation-only view of it now would be a surface that has to be replaced rather
 *       than extended. The rows are written and indexed for exactly the two questions
 *       that will be asked of them — see V14.
 *   <li><strong>No role model.</strong> Who may curate is the configured moderator list,
 *       for the reason §10.2 gives about moderation. See {@link CurationService}.
 * </ul>
 *
 * <h2>Verbs</h2>
 *
 * <p>Publishing, adding, and removing are {@code POST} to a named sub-path rather than
 * {@code PATCH} or {@code DELETE}, following the three moderation endpoints. Each
 * carries a required note, and RFC 9110 gives a {@code DELETE} body no defined
 * semantics — a required explanation in a request some proxies discard is a required
 * explanation that goes missing.
 */
@RestController
@RequestMapping("/v1/admin/collections")
public class AdminCurationController {

    private final CurationService curation;

    public AdminCurationController(CurationService curation) {
        this.curation = curation;
    }

    /** Every collection, published or not, in placement order. */
    @GetMapping
    public CollectionResponses.AdminCollectionIndex list(@AuthenticationPrincipal Jwt accessToken) {
        return new CollectionResponses.AdminCollectionIndex(curation.list(curatorOf(accessToken)).stream()
                .map(CollectionResponses::admin)
                .toList());
    }

    /** One collection with its copy and its membership, including campaigns the public cannot see. */
    @GetMapping("/{slug}")
    public CollectionResponses.AdminCollectionResponse read(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable String slug) {

        return CollectionResponses.admin(curation.read(slug, curatorOf(accessToken)));
    }

    /** A new, empty, unpublished collection. */
    @PostMapping
    public CollectionResponses.AdminCollectionResponse create(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody CurationRequests.CreateCollection request) {

        return CollectionResponses.admin(curation.create(
                request.slug(), request.collection().toDraft(), curatorOf(accessToken)));
    }

    /** Replaces the whole description: kind, window, badge grant, placement, imagery, copy. */
    @PutMapping("/{slug}")
    public CollectionResponses.AdminCollectionResponse replace(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @Valid @RequestBody CurationRequests.CollectionBody request) {

        return CollectionResponses.admin(curation.replace(slug, request.toDraft(), curatorOf(accessToken)));
    }

    /** Makes the list visible to the public. The note is required and is audited. */
    @PostMapping("/{slug}/publish")
    public CollectionResponses.AdminCollectionResponse publish(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @Valid @RequestBody CurationRequests.PublishCollection request) {

        return CollectionResponses.admin(
                curation.setPublished(slug, true, request.note(), curatorOf(accessToken)));
    }

    /** Takes it back down. Also audited, and also with a reason. */
    @PostMapping("/{slug}/unpublish")
    public CollectionResponses.AdminCollectionResponse unpublish(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @Valid @RequestBody CurationRequests.PublishCollection request) {

        return CollectionResponses.admin(
                curation.setPublished(slug, false, request.note(), curatorOf(accessToken)));
    }

    /**
     * Curates a campaign into the list, at the end of it.
     *
     * <p>In a badge-granting collection this <em>is</em> §3.2's "apply an editorial
     * badge", which is why the note is required rather than optional.
     */
    @PostMapping("/{slug}/projects")
    public CollectionResponses.AdminCollectionResponse addProject(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @Valid @RequestBody CurationRequests.AddProject request) {

        return CollectionResponses.admin(
                curation.addProject(slug, request.projectId(), request.note(), curatorOf(accessToken)));
    }

    /** Takes a campaign out, and its badge with it. */
    @PostMapping("/{slug}/projects/{projectId}/remove")
    public CollectionResponses.AdminCollectionResponse removeProject(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @PathVariable UUID projectId,
            @Valid @RequestBody CurationRequests.RemoveProject request) {

        return CollectionResponses.admin(
                curation.removeProject(slug, projectId, request.note(), curatorOf(accessToken)));
    }

    /**
     * Restates the whole sequence.
     *
     * <p>{@code PUT}, because it is idempotent and it replaces the order rather than
     * nudging it: the body names every campaign in the collection exactly once, and a
     * partial order would leave the rest wherever they happened to be.
     */
    @PutMapping("/{slug}/projects/order")
    public CollectionResponses.AdminCollectionResponse reorder(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @Valid @RequestBody CurationRequests.ReorderProjects request) {

        return CollectionResponses.admin(curation.reorder(slug, request.projectIds(), curatorOf(accessToken)));
    }

    /**
     * Whoever is signed in.
     *
     * <p>Checked against the configured moderator list by {@link CurationService} before
     * anything is loaded, and recorded on the audit row either way — the trail's job is
     * to say which account took the decision, and a refused attempt never reaches one.
     */
    private static UUID curatorOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
