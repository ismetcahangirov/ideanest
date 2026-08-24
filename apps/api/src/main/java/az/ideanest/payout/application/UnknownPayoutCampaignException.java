package az.ideanest.payout.application;

import java.util.UUID;

/**
 * A payout was to be calculated for a campaign that does not exist - #69.
 *
 * <p>404, and deliberately the same answer for a deleted campaign as for an identifier
 * that never named one - the line AdminUserController draws for accounts.
 */
public class UnknownPayoutCampaignException extends RuntimeException {

    public UnknownPayoutCampaignException(UUID projectId) {
        super("No campaign " + projectId);
    }
}
