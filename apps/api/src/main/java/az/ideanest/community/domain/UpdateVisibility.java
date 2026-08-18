package az.ideanest.community.domain;

/**
 * Who an update is written for. §4.7's CD-12: "public or backers-only".
 *
 * <p><strong>Two values rather than a boolean.</strong> {@code backersOnly = false}
 * reads as an absence, and the thing it would be an absence of — being published to
 * the whole internet — is the more consequential of the two. A third audience is
 * plausible (a tier, a survey cohort) and would be a value here rather than a second
 * boolean beside the first.
 *
 * <p>Stored as text against a checked column rather than as an ordinal, so that a
 * row read outside the application says what it means and a value inserted by hand
 * is refused by the database rather than accepted as a number.
 */
public enum UpdateVisibility {

    /** Readable by anybody who can read the campaign, signed in or not. */
    PUBLIC,

    /**
     * Readable by the people who backed the campaign, and by the team that runs it.
     *
     * <p><strong>Not yet enforceable against backers.</strong> Deciding whether a
     * caller has an active pledge on this campaign is a question about {@code pledges},
     * and the pledge module publishes no answer to it — see {@code ProjectUpdateService}
     * for what this release does instead, and why the direction it fails in is the safe
     * one.
     */
    BACKERS_ONLY
}
