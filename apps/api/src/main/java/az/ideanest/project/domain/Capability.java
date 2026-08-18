package az.ideanest.project.domain;

import az.ideanest.shared.access.ProjectCapability;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

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
 *
 * <p><strong>This enum stays private to this module, and the vocabulary does
 * not.</strong> Every constant carries its counterpart in
 * {@link ProjectCapability}, which is the published contract another module names
 * when it asks {@code ProjectAccess} for a capability check. The pairing is
 * declared on the constants rather than derived from {@link Enum#name()}, so a
 * capability added on one side and not the other is a start-up failure with both
 * enums in the message — see the static initialiser below — instead of a
 * {@code valueOf} that throws on the first request that happens to need it.
 *
 * <p>Two enums rather than one because they are two different things that happen
 * to agree: this one is what is stored, validated by
 * {@code collaborator_capabilities_known}, and reasoned over by {@link Grants};
 * the other is a list of names other modules may say out loud.
 */
public enum Capability {

    /** Title, summary, category, funding goal, duration, cover image. */
    EDIT_BASICS(ProjectCapability.EDIT_BASICS),

    /** Items, reward tiers, shipping rules — what a backer is promised. */
    EDIT_REWARDS(ProjectCapability.EDIT_REWARDS),

    /** The story document and the mandatory risks section. */
    EDIT_STORY(ProjectCapability.EDIT_STORY),

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
    SUBMIT_FOR_REVIEW(ProjectCapability.SUBMIT_FOR_REVIEW),

    /** Post numbered updates to backers. */
    PUBLISH_UPDATES(ProjectCapability.PUBLISH_UPDATES),

    /** Reply to comments and questions as the campaign. */
    RESPOND_TO_COMMENTS(ProjectCapability.RESPOND_TO_COMMENTS),

    /**
     * See the backer report and the money in it.
     *
     * <p>The one capability on this list that is about reading rather than
     * writing, and the one most worth granting narrowly: it exposes what the
     * campaign has raised and who committed it.
     */
    VIEW_FINANCES(ProjectCapability.VIEW_FINANCES),

    /**
     * Invite collaborators and revoke their grants.
     *
     * <p><strong>Only the creator may grant this one.</strong> A collaborator who
     * could pass it on could build a team the creator never approved, and the
     * creator would be one revocation behind for as long as it took them to
     * notice. Holding it is enough to invite and to revoke; conferring it is not.
     */
    MANAGE_COLLABORATORS(ProjectCapability.MANAGE_COLLABORATORS);

    /**
     * Every published name, mapped back to the constant that decides it.
     *
     * <p>Built from the constants rather than written out a second time, and checked
     * for totality here rather than at the call site: a published capability with no
     * counterpart would otherwise be a request that reaches
     * {@code ProjectAccess} and fails with a null, which is a permission bug wearing a
     * {@code NullPointerException}. Failing at class initialisation makes it a build
     * and start-up failure instead.
     */
    private static final Map<ProjectCapability, Capability> BY_PUBLISHED_NAME;

    static {
        EnumMap<ProjectCapability, Capability> mapped = new EnumMap<>(ProjectCapability.class);
        for (Capability capability : values()) {
            mapped.put(capability.published, capability);
        }
        if (mapped.size() != ProjectCapability.values().length) {
            throw new ExceptionInInitializerError(
                    "Every ProjectCapability must map to a Capability; mapped " + mapped.keySet());
        }
        BY_PUBLISHED_NAME = Collections.unmodifiableMap(mapped);
    }

    private final ProjectCapability published;

    Capability(ProjectCapability published) {
        this.published = published;
    }

    /** How this capability is named in the contract other modules ask through. */
    public ProjectCapability published() {
        return published;
    }

    /**
     * The capability a caller outside this module asked for.
     *
     * <p>The one place the published vocabulary becomes the deciding one, so that
     * {@code ProjectAccess} is the only class that has to know there are two enums.
     */
    public static Capability of(ProjectCapability published) {
        return BY_PUBLISHED_NAME.get(published);
    }
}
