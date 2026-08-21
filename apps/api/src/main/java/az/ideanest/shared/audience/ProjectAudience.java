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
 * <p><strong>Three constants, and #90 is what made the other two honest.</strong> This enum used
 * to hold one, and said so at length: {@code BACKERS} existed because {@code pledges} existed,
 * and a {@code FOLLOWERS} value would have been a published vocabulary word meaning "not yet" —
 * one every implementation had to refuse and every caller had to handle. #90 built {@code saves}
 * and {@code follows}, so both now have rows behind them and a module that answers for them.
 *
 * <p>Adding a constant is a decision and a released implementation, in that order. That is no
 * longer only a convention: {@link RoutedProjectAudiences} refuses to start when a constant here
 * has no {@link ProjectAudienceSource} claiming it, so a value nothing can answer fails a
 * deployment rather than a delivery.
 *
 * <p><strong>The three are not disjoint and no caller may assume they are.</strong> Somebody can
 * back a campaign they saved and follow the person running it, and would then be in all three.
 * Deduplicating is the caller's job and it is not optional: {@code notifications} is unique on
 * (event, recipient, channel), so an audience union with a repeat in it fails an insert and
 * rolls back a dispatch shared with every other module. {@code NotificationEventListener} does
 * it in one place, for that reason.
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
    BACKERS,

    /**
     * Everybody who saved this campaign — §4.9's C-09.
     *
     * <p>§4.10's "saved project ending soon", and nothing else so far. <strong>Interest, not
     * commitment</strong>, which is what makes this the wrong audience for most of §4.10: a
     * saver has promised nothing, so a message about money, fulfilment or a payout is not
     * theirs. Answered by the community module, which owns {@code saves}.
     */
    SAVERS,

    /**
     * Everybody following the account this campaign belongs to — §4.9's C-10.
     *
     * <p>§4.10's "followed creator launched". <strong>The audience is the creator's, and the
     * question is the campaign's</strong>, which is the one place this enum's project-shaped
     * key is a slight fit rather than an obvious one: a follow is a row about two accounts, and
     * it is reached here by asking who a campaign belongs to. The alternative was a second,
     * creator-shaped port used by exactly one notification — a published surface doubled to
     * avoid one join. {@code CommunityProjectAudiences} is where the join happens and it uses
     * the published {@code ProjectSummaries} rather than reading {@code projects}.
     *
     * <p><strong>A creator never follows themselves</strong>, so this audience never contains
     * the campaign's own creator; {@code follows_is_not_self} is what guarantees it rather than
     * a filter somebody has to remember.
     */
    FOLLOWERS
}
