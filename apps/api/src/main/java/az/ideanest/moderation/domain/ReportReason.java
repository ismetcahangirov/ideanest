package az.ideanest.moderation.domain;

/**
 * Why somebody is reporting something. §5.4's taxonomy, plus the two reasons that
 * are not about the goods.
 *
 * <p><strong>A closed set, and closed in the database too.</strong> That is the
 * opposite of the decision V19 and V21 take for {@code outbox_events.event_type} and
 * {@code audit_logs.action}, and the difference is who owns the set. An audit action
 * is added by whichever feature adds a privileged action, so a new one must not be a
 * migration and a deployment-ordering problem. A report reason is a product and
 * legal decision: it appears in a dropdown, in §21.1's four locales, and in the
 * order a moderator triages by — the queue has to be taught it either way, so
 * letting the writing side invent one buys nothing and costs a taxonomy that cannot
 * be counted.
 *
 * <p><strong>Free text is not a substitute.</strong> A reason field somebody types
 * into cannot be sorted, cannot be counted, and cannot be translated, which means a
 * queue ordered by "how bad is this" is a queue ordered by nothing. {@link #OTHER}
 * is the escape hatch and it is the only one that requires the reporter to write
 * prose — see {@link #requiresDetail()}.
 */
public enum ReportReason {

    /**
     * §5.4's list of what may not be funded at all: drugs, tobacco and nicotine;
     * weapons, accessories and replicas; alcohol as a reward; contests, lotteries
     * and raffles; pornography; political fundraising; medical claims; and the rest
     * of it.
     *
     * <p>One value rather than fourteen. The distinctions matter to the moderator
     * reading the campaign, not to the backer pressing the button, and a dropdown
     * with fourteen entries is a dropdown where everybody picks the first one.
     */
    PROHIBITED_ITEM,

    /** §5.4: "no project may misrepresent facts". */
    MISREPRESENTATION,

    /**
     * §5.4: every reward must be new and unique, produced or designed by the
     * project or a collaborator. Resale goods, repackaged products, and
     * already-existing ones.
     */
    NOT_ORIGINAL,

    /**
     * Somebody else's work, passed off as the project's own.
     *
     * <p>Told apart from {@link #NOT_ORIGINAL} because the two send a moderator in
     * different directions: a repackaged product is a policy question this platform
     * decides, and an infringement claim is a legal one it has to route.
     */
    INTELLECTUAL_PROPERTY,

    /** §5.4: "offensive material". */
    OFFENSIVE,

    /** §5.4: "discriminatory content". Kept apart from {@link #OFFENSIVE}, as §5.4 keeps it. */
    DISCRIMINATION,

    /**
     * Not §5.4, and the reason a comment gets reported.
     *
     * <p>Reportable on a campaign as well: a campaign whose only content is a link
     * somewhere else is the same problem wearing a bigger page.
     */
    SPAM,

    /**
     * AD-02's "fraud signals", and R6's fraudulent creator.
     *
     * <p>The most expensive reason on this list and the one worth telling apart from
     * {@link #MISREPRESENTATION}: an exaggerated claim is a campaign to correct, and
     * a fraudulent creator is money to stop moving.
     */
    FRAUD,

    /**
     * Something the eight above do not cover.
     *
     * <p>Present because a taxonomy with no escape hatch produces reports filed
     * under the nearest wrong heading, which is worse than an honest {@code OTHER}:
     * the wrong heading is counted, and the count is what a moderator triages by.
     */
    OTHER;

    /**
     * Whether the reporter has to write what they mean.
     *
     * <p>True for {@link #OTHER} alone. A report that says "other" and nothing else
     * cannot be acted on, and a queue of them is a queue that gets ignored — which
     * costs the reports that could have been acted on, not just these. The rule is
     * also a {@code CHECK} in V23, for the reason every rule in this module is in
     * both places.
     */
    public boolean requiresDetail() {
        return this == OTHER;
    }
}
