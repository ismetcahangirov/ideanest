package az.ideanest.pledge.domain;

/**
 * What a backer bought after the campaign closed — §4.8's PM-09 and PM-10.
 *
 * <p>Two kinds rather than one amount, because the creator's two questions are
 * different: an {@link #UPGRADE} changes what goes in a box that was already going to
 * be packed, and {@link #ADDONS} changes how full it is. A single "extra purchase"
 * kind would make "how many upgraded" a query over the tier columns rather than a
 * count.
 *
 * <p>The names are the wire format and the stored value, checked by
 * {@code pledge_supplements_kind_is_known}. Renaming one is a migration.
 */
public enum SupplementKind {

    /**
     * PM-09: the pledge moved to a better reward tier.
     *
     * <p>Carries both tiers, which {@code pledge_supplements_tiers_match_the_kind}
     * enforces: the pledge's own {@code reward_tier_id} moves, so without the pair on
     * this row nothing records what it moved from.
     */
    UPGRADE,

    /** PM-10: more things bought beside the reward, with their own lines. */
    ADDONS
}
