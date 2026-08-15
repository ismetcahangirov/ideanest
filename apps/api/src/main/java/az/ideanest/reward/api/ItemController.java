package az.ideanest.reward.api;

import az.ideanest.reward.application.ItemService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The atomic items a creator's campaign is built from.
 *
 * <p>Items before tiers, which is the order §4.6 puts them in: a tier is a selection of
 * items with quantities, so there is nothing to compose until these exist.
 *
 * <p>Authorisation is not decided here. Every method passes the caller's identifier
 * down, and {@code RewardAccess} finds the campaign the item belongs to and asks
 * {@code ProjectAccess} — the one place in the service where "who may edit this
 * campaign" is settled, so that #38 widening it to collaborators changes one file rather
 * than eight methods.
 *
 * <p>The subject of the access token is where the caller comes from, never a path or
 * body parameter. A creator identifier a client could choose would let anybody edit
 * anybody's campaign.
 *
 * <p><strong>No {@code Location} header on create</strong>, unlike
 * {@code POST /v1/projects}. There is no single-item read endpoint — the editor reads
 * the collection, because it needs all of them to compose a tier — and a header pointing
 * at a URL that answers 404 is worse than no header.
 */
@RestController
public class ItemController {

    private final ItemService items;

    public ItemController(ItemService items) {
        this.items = items;
    }

    /**
     * Every item of the campaign, oldest first.
     *
     * <p>Not in the epic's endpoint list, and the rewards editor cannot work without it:
     * the tier form offers a list of items to include, and after a page reload the client
     * has no other way to obtain one. Named as an addition in the pull request.
     */
    @GetMapping("/v1/projects/{projectId}/items")
    public List<ItemResponse> list(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {
        return items.list(projectId, callerOf(accessToken)).stream()
                .map(ItemResponse::of)
                .toList();
    }

    @PostMapping("/v1/projects/{projectId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateItemRequest request) {

        return ItemResponse.of(items.create(projectId, callerOf(accessToken), request.toDraft()));
    }

    /**
     * Applies a partial edit and returns the whole item.
     *
     * <p>The whole item rather than the fields that changed, because an edit can change
     * a field the client did not send — making an item digital clears its weight — and a
     * client that merged a partial response would keep a value the server has discarded.
     */
    @PatchMapping("/v1/items/{id}")
    public ItemResponse edit(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id, @RequestBody ItemPatchRequest request) {

        return ItemResponse.of(items.edit(id, callerOf(accessToken), request.toPatch()));
    }

    /**
     * Removes an item.
     *
     * <p>{@code 409 ITEM_IN_USE} when a reward tier contains it: deleting it would
     * change what a backer was promised, so the creator takes it out of each tier first.
     */
    @DeleteMapping("/v1/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt accessToken, @PathVariable UUID id) {
        items.delete(id, callerOf(accessToken));
    }

    /** The account making the request, as our own signature establishes it. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
