package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * The campaign is still running, so the pledge manager is not the way to change this
 * pledge — §4.8's PM-09 and PM-10.
 *
 * <p><strong>A refusal that names the alternative.</strong> While a campaign takes
 * pledges, §4.5's PL-09 edit re-quotes the whole pledge and nothing has been charged;
 * afterwards, §5.1's decision has been frozen against those numbers and a further
 * purchase is a separate transaction. Both paths exist, exactly one of them applies at
 * any moment, and which one is decided by the campaign rather than by the client — so
 * the refusal has to say so, or a client will offer an upgrade button that never works
 * until the deadline passes.
 */
public class CampaignStillTakingPledgesException extends RuntimeException {

    public CampaignStillTakingPledgesException(UUID projectId) {
        super("Campaign " + projectId + " is still taking pledges");
    }
}
