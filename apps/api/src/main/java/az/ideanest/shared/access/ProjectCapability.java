package az.ideanest.shared.access;

/**
 * The published names of the things a collaborator may be permitted to do on a
 * campaign.
 *
 * <p><strong>This is the vocabulary, not the rules.</strong> Whether a particular
 * account holds a particular capability on a particular campaign is decided by the
 * project module and nowhere else — see {@link ProjectAuthorisation}. What this enum
 * does is let a caller in another module <em>say which one it needs</em>, which before
 * it existed was the one thing they could not say: the deciding enum is
 * {@code project.domain.Capability}, and {@code ModuleBoundaryTests} forbids reaching
 * into another module's {@code domain} package.
 *
 * <p><strong>It is one-for-one with that enum, and a test holds it there.</strong>
 * {@code project.domain.Capability} carries the counterpart of each of these on its
 * constants and refuses to load if one is unmapped, and
 * {@code ProjectCapabilityContractTests} asserts the two name sets are identical in
 * both directions. Two enums are the cost of publishing a vocabulary without
 * publishing the module's internals; a drift between them is the risk that buys, and
 * it is the risk the test removes.
 *
 * <p><strong>The names are the wire format</strong>, as they are on the internal enum:
 * they are what the API accepts and returns, what {@code collaborator_capabilities}
 * stores, and what the campaign editor's People tab renders. Renaming one is a
 * breaking change to a client and to every stored row.
 *
 * <p>The creator of a campaign holds all of them implicitly and holds them without a
 * grant row, so a check against any of these passes for them.
 */
public enum ProjectCapability {

    /** Title, summary, category, funding goal, duration, cover image. */
    EDIT_BASICS,

    /** Items, reward tiers, shipping rules — what a backer is promised. */
    EDIT_REWARDS,

    /** The story document and the mandatory risks section. */
    EDIT_STORY,

    /**
     * Send the campaign to moderation.
     *
     * <p>Launching and cancelling are deliberately <strong>not</strong> capabilities:
     * both are irreversible money decisions and stay with the account that owns the
     * campaign.
     */
    SUBMIT_FOR_REVIEW,

    /** Post numbered updates to backers. */
    PUBLISH_UPDATES,

    /** Reply to comments and questions as the campaign. */
    RESPOND_TO_COMMENTS,

    /**
     * Write, edit, reorder and remove the campaign's FAQ entries — §4.4's FAQ tab.
     *
     * <p>Its own capability rather than a corner of {@code EDIT_BASICS}, for the reason
     * {@code ProjectAccess} records about coarse checks on published surfaces: the one
     * that is offered is the one that gets taken. An FAQ entry is text published in the
     * campaign's name to everybody reading its page, which is nearer to
     * {@link #PUBLISH_UPDATES} than to the funding goal.
     */
    MANAGE_FAQ,

    /**
     * See the backer report, the referral report, and the money in both.
     *
     * <p>The one capability here that is about reading rather than writing, and the one
     * most worth granting narrowly: it exposes what the campaign has raised and where
     * it came from.
     */
    VIEW_FINANCES,

    /**
     * Invite collaborators and revoke their grants.
     *
     * <p>Only the creator may confer this one. Holding it is enough to invite and to
     * revoke; passing it on is not.
     */
    MANAGE_COLLABORATORS
}
