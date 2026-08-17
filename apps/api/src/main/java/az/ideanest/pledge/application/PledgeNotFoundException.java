package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * No such pledge, or none belonging to this backer.
 *
 * <p><strong>Somebody else's pledge is a 404 and not a 403</strong>, which is
 * {@code RewardNotFoundException}'s reasoning applied unchanged. Telling a stranger
 * that a pledge exists is the disclosure: an identifier that answers "forbidden"
 * confirms that somebody backed something, and a caller walking identifiers could
 * establish which campaigns have backers and how many, one 403 at a time. The two
 * cases are one answer, so there is nothing to walk.
 *
 * <p>A pledge is also more private than a reward tier, not less. It says who gave
 * money, how much, and whether they asked to be anonymous while doing it (§4.5's
 * PL-12) — and PL-12 would mean very little if the pledge behind it could be
 * confirmed to exist by anybody holding its identifier.
 */
public class PledgeNotFoundException extends RuntimeException {

    public PledgeNotFoundException(UUID pledgeId) {
        // The identifier and nothing else: this message reaches a log, and what a
        // person pledged is the confidential part.
        super("No pledge " + pledgeId + " is visible to this caller");
    }
}
