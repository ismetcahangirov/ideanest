package az.ideanest.pledge.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Which reward tier a backer is moving up to — §4.8's PM-09.
 *
 * <p>One field, and no amount. What the upgrade costs is the difference between the
 * two tiers priced at the same moment, and a client that sent a figure would be
 * quoting itself: the price a backer sees may be an hour old, and the number that
 * matters is the one somebody will be charged.
 */
public record UpgradePledgeRequest(
        @NotNull(message = "An upgrade names the reward tier to move to") UUID rewardTierId) {
}
