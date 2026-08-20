package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * No such saved segment on this campaign.
 *
 * <p>A 404, and it is raised for a segment that belongs to <em>another</em> campaign as
 * well as for one that does not exist. The caller has already been authorised on the
 * campaign in the path, so answering "that segment is real, but not yours" would confirm
 * an identifier from somebody else's dashboard.
 */
public class BackerSegmentNotFoundException extends RuntimeException {

    public BackerSegmentNotFoundException(UUID segmentId) {
        super("No segment " + segmentId + " on that campaign.");
    }
}
