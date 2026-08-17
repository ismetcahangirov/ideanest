package az.ideanest.pledge.application;

import java.util.UUID;

/**
 * How many people have backed one reward tier — §4.4's Rewards tab, which puts a
 * backer count beside every tier's price and remaining quantity.
 *
 * <p><strong>A count and not a list.</strong> Which tier somebody chose is not public,
 * for the reason {@link PublicBacker} gives: on a campaign with a tier that one person
 * took, naming the tier alongside the backer would identify them, and it would do it
 * to the backer who asked to be anonymous just as readily as to the one who did not.
 * An aggregate says what a visitor needs — this tier is popular, that one is not —
 * without saying it about anybody.
 *
 * <p>Lives in {@code application} rather than beside the query that produces it,
 * because this is the meaning rather than the mechanism, and because it is what
 * {@link PublicBackers} publishes. {@code PublicBackerRepository} names it in a JPQL
 * constructor expression; a repository returning its own module's value types is not a
 * layering inversion, and the boundary {@code ModuleBoundaryTests} is defending is the
 * one between modules.
 *
 * @param rewardTierId never null — the grouped query excludes the pledges that name no
 *     tier, which are §4.5's PL-02 support-only backings
 * @param backerCount the number of pledges in one of {@link PublicBackers#COUNTED}'s
 *     states, anonymous ones included
 */
public record RewardTierBackers(UUID rewardTierId, long backerCount) {
}
