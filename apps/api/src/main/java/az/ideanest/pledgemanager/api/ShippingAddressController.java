package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.AddressCollectionProgress;
import az.ideanest.pledgemanager.application.ShippingAddressService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.8's PM-07 and PM-08 over HTTP.
 *
 * <p>Four endpoints on two resources, because there are two actors. A backer reads and
 * writes the address on <em>their</em> pledge; a creator locks the campaign's and
 * watches the count. Neither endpoint answers the other's question, and no endpoint
 * here returns another backer's address at all — the creator's fulfilment list is
 * PM-17's, it is audited, and it is a different issue.
 *
 * <p>Every response is {@code no-store}. That is not the usual "behind a capability"
 * reasoning: the body is somebody's home address, and a shared cache or a browser
 * disk cache holding one is a disclosure that survives signing out.
 *
 * <p>Bearer tokens throughout, by falling through to {@code SecurityConfiguration}'s
 * catch-all.
 */
@RestController
public class ShippingAddressController {

    private final ShippingAddressService addresses;

    public ShippingAddressController(ShippingAddressService addresses) {
        this.addresses = addresses;
    }

    /**
     * The address on one of the caller's own pledges.
     *
     * <p>204 when they have not given one, rather than 404 or an empty object: the
     * pledge exists and the address does not, which is a different fact from "no such
     * pledge" and the one a form needs in order to render itself blank.
     */
    @GetMapping("/v1/pledges/{pledgeId}/shipping-address")
    public ResponseEntity<ShippingAddressResponse> read(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID pledgeId) {

        return addresses
                .readOwn(pledgeId, callerOf(accessToken))
                .map(stored -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ShippingAddressResponse.of(stored)))
                .orElseGet(() -> ResponseEntity.noContent()
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    /**
     * Records where this pledge's reward goes.
     *
     * <p><strong>{@code PATCH} with a whole address, which looks like a contradiction
     * and is the contract §10.2 names.</strong> The body replaces the address entirely
     * — an omitted {@code line2} clears it — because merging a partial address is how
     * somebody who moved house ends up with the old flat number on the new street. What
     * makes it a PATCH rather than a PUT is the resource: the pledge is what is being
     * modified, and the address is one part of it.
     *
     * <p>200 rather than 201 even on the first write, for the same reason: no new
     * resource comes into existence, the pledge gains a field.
     */
    @PatchMapping("/v1/pledges/{pledgeId}/shipping-address")
    public ResponseEntity<ShippingAddressResponse> save(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID pledgeId,
            @RequestBody PostalAddressBody request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ShippingAddressResponse.of(
                        addresses.save(pledgeId, callerOf(accessToken), request.toAddress())));
    }

    /**
     * PM-08: freezes every address on the campaign.
     *
     * <p>{@code POST} to an action rather than {@code PATCH} on a collection, because
     * it is not idempotent in the way a client would assume — the second call locks
     * nothing and reports zero, which is true and is not what a repeated PATCH means.
     *
     * <p>No unlock-all counterpart, deliberately. Reopening is per address and belongs
     * with the support conversation that prompts it; a button that unlocks four
     * thousand at once is a button somebody presses after the labels are printed.
     */
    @PostMapping("/v1/projects/{projectId}/shipping-addresses/lock")
    public ResponseEntity<LockAddressesResponse> lock(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        UUID caller = callerOf(accessToken);
        int locked = addresses.lockAll(projectId, caller);
        AddressCollectionProgress progress = addresses.progressOf(projectId, caller);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LockAddressesResponse(locked, progress.editable()));
    }

    /**
     * How many addresses the campaign has, and how many are still editable.
     *
     * <p>The one read in this feature that decrypts nothing and names nobody, which is
     * why a dashboard may poll it.
     */
    @GetMapping("/v1/projects/{projectId}/shipping-addresses/progress")
    public ResponseEntity<AddressProgressResponse> progress(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable UUID projectId) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AddressProgressResponse.of(addresses.progressOf(projectId, callerOf(accessToken))));
    }

    /** The account making the request, from our own signature and never from the body. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
