package az.ideanest.project.domain;

/**
 * One thing a collaborator may be permitted to do on a campaign.
 *
 * <p>§3.1's permission matrix gives collaborators the creator's abilities
 * "subject to the granular grants the creator issued", and this enum is that
 * granularity. It is deliberately a list of <em>actions</em> rather than of
 * roles: a creator who wants a copywriter to rewrite the story and touch nothing
 * else can say exactly that, whereas an "editor" role would have to be defined
 * once for everybody and would always be wrong for somebody.
 *
 * <p><strong>The names are the wire format.</strong> They are what the API
 * accepts and returns, and the campaign editor's People tab renders them, so
 * renaming one is a breaking change to a client and to every stored row —
 * {@code collaborator_capabilities_known} lists the same eight strings.
 *
 * <p>The creator holds all of them implicitly and holds them without a row. See
 * {@link Grants}.
 */
public enum Capability {

    /** Title, summary, category, funding goal, duration, cover image. */
    EDIT_BASICS,

    /** Items, reward tiers, shipping rules — what a backer is promised. */
    EDIT_REWARDS,

    /** The story document and the mandatory risks section. */
    EDIT_STORY,

    /**
     * Send the campaign to moderation.
     *
     * <p>Separate from {@link #EDIT_BASICS} because it is the last moment the
     * creator can still change their mind privately: submission puts the
     * campaign in front of platform staff, and it is reasonable to let somebody
     * write a campaign without letting them decide it is finished.
     *
     * <p>Launching and cancelling are deliberately <strong>not</strong>
     * capabilities. Both are irreversible money decisions — going live starts
     * accepting pledges, cancelling abandons commitments people have already
     * made — and they stay with the account that owns the campaign.
     */
    SUBMIT_FOR_REVIEW,

    /** Post numbered updates to backers. */
    PUBLISH_UPDATES,

    /** Reply to comments and questions as the campaign. */
    RESPOND_TO_COMMENTS,

    /**
     * See the backer report and the money in it.
     *
     * <p>The one capability on this list that is about reading rather than
     * writing, and the one most worth granting narrowly: it exposes what the
     * campaign has raised and who committed it.
     */
    VIEW_FINANCES,

    /**
     * Invite collaborators and revoke their grants.
     *
     * <p><strong>Only the creator may grant this one.</strong> A collaborator who
     * could pass it on could build a team the creator never approved, and the
     * creator would be one revocation behind for as long as it took them to
     * notice. Holding it is enough to invite and to revoke; conferring it is not.
     */
    MANAGE_COLLABORATORS
}
