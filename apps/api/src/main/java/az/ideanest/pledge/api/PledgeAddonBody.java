package az.ideanest.pledge.api;

import az.ideanest.pledge.application.DraftPledge;
import az.ideanest.pledge.domain.PledgeAddon;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * One add-on and how many of it, on the way in and on the way out.
 *
 * <p>One record for both directions because it is the same fact in both: a client
 * that sends {@code [{"rewardTierId": "...", "quantity": 2}]} gets the same shape
 * back, and can compare what it sent with what was recorded without a second mapping.
 *
 * @param quantity at least one. A line for none of something is a line the client
 *     should have left out, and {@code pledge_addons_quantity_is_positive} refuses
 *     the row
 */
public record PledgeAddonBody(
        @NotNull(message = "An add-on names a reward tier") UUID rewardTierId,
        @Min(value = 1, message = "An add-on is selected at least once") int quantity) {

    /**
     * What the checkout takes.
     *
     * <p><strong>The null check is not redundant, since #56.</strong> On the draft the
     * {@code @NotNull} above is enforced by {@code @Valid} and this can never see a
     * line without a tier. On {@code PATCH} it can: bean validation cannot look inside
     * a {@code Patched}, so nothing has checked the list by the time it arrives here,
     * and a line sent as {@code {"quantity": 2}} would otherwise become a
     * {@code NullPointerException} out of {@code AddonSelection} — a 500 for a request
     * whose fault is entirely the client's.
     */
    public static List<DraftPledge.AddonSelection> selectionsOf(List<PledgeAddonBody> bodies) {
        if (bodies == null) {
            return List.of();
        }
        return bodies.stream()
                .map(body -> {
                    if (body == null || body.rewardTierId() == null) {
                        throw new IllegalArgumentException("An add-on names a reward tier");
                    }
                    return new DraftPledge.AddonSelection(body.rewardTierId(), body.quantity());
                })
                .toList();
    }

    /** What was recorded. */
    public static List<PledgeAddonBody> of(List<PledgeAddon> addons) {
        return addons.stream()
                .map(addon -> new PledgeAddonBody(addon.getRewardTierId(), addon.getQuantity()))
                .toList();
    }
}
