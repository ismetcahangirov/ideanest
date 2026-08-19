package az.ideanest.shared.audience;

/**
 * A group of people defined by their relationship to a campaign.
 *
 * <p>Named as a value rather than as a method per audience, which is the shape #236 established
 * for {@code ProjectCapability} and the argument is the same one: a predicate per audience keeps
 * the owning module's internals private at the price of a published surface that grows without
 * bound and has to be extended, reviewed and released before a caller may ask a question the
 * data already answers.
 *
 * <p><strong>One constant, and that is the honest size of this enum.</strong> #245 names two
 * audiences and only one of them has anything to enumerate:
 *
 * <ul>
 *   <li><strong>{@link #BACKERS} is here because {@code pledges} exists.</strong> The pledge
 *       module implements it against rows that have been there since V17.
 *   <li><strong>Followers are not here, and adding the constant now would be the mistake
 *       #244 was about.</strong> #90 owns saving and following; until it lands there is no
 *       table to read, so a {@code FOLLOWERS} value would be one every implementation had to
 *       refuse and every caller had to handle — a published vocabulary word that means "not
 *       yet". §4.10's {@code FOLLOWED_CREATOR_LAUNCHED} and
 *       {@code SAVED_PROJECT_ENDING_SOON} therefore still have no audience, which is a
 *       missing feature rather than a broken one.
 * </ul>
 *
 * <p>Adding a constant is a decision and a released implementation, in that order. A value in
 * here that nothing can answer is worse than no value, because a caller writes code against it.
 */
public enum ProjectAudience {

    /**
     * Everybody whose pledge to this campaign is a live commitment.
     *
     * <p>§4.10's "goal reached", "48 hours remaining", "campaign succeeded" — every row whose
     * audience is the people who put money behind it.
     *
     * <p><strong>Which pledge states count is the pledge module's decision, not this
     * enum's</strong>, and {@code PledgeProjectAudiences} states it: the commitment has to be
     * live, so a lapsed reservation, a cancellation, a dropped charge and a refund are all out,
     * and so is a draft — somebody holding a place at a checkout they have not finished has not
     * backed anything yet, and telling them the campaign reached its goal would be telling them
     * about their own money.
     */
    BACKERS
}
