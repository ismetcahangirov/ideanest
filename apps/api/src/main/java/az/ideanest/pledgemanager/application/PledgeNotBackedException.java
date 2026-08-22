package az.ideanest.pledgemanager.application;

import java.util.UUID;

/**
 * A pledge that does not exist, is not a backing, or is not this caller's.
 *
 * <p><strong>One exception for three cases, deliberately.</strong> Distinguishing them
 * would turn the endpoint into an oracle: a caller could learn which pledge
 * identifiers exist, and — since a pledge is one per backer per campaign — which
 * accounts backed which campaigns, which is exactly what §4.5's PL-12 spends a column
 * on not revealing.
 *
 * <p>404, for the same reason.
 */
public class PledgeNotBackedException extends RuntimeException {

    private final UUID pledgeId;

    public PledgeNotBackedException(UUID pledgeId) {
        super("No such pledge: " + pledgeId);
        this.pledgeId = pledgeId;
    }

    public UUID pledgeId() {
        return pledgeId;
    }
}
