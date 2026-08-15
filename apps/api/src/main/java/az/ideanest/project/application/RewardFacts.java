package az.ideanest.project.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What this module needs to know about another module's reward tiers.
 *
 * <p>§5.3 puts two of its completeness rules on rewards — a campaign offers
 * between zero and a hundred tiers, and every tier is priced at or above the
 * smallest chargeable amount — so the checklist cannot be evaluated without
 * asking the reward module something.
 *
 * <p><strong>Why a port here rather than a call there.</strong> The reward module
 * already depends on this one: {@code RewardAccess} asks {@link ProjectAccess}
 * whether a caller may edit the campaign a tier belongs to. A call in the other
 * direction would make the two modules mutually dependent, which
 * {@code ModuleBoundaryTests} refuses outright — {@code modulesAreAcyclic} — and
 * which would be right to refuse: two modules that depend on each other are one
 * module with a naming convention, and neither can be extracted afterwards.
 *
 * <p>So the interface is declared by the module that needs the answer and
 * implemented by the module that has it. The dependency arrow stays pointing one
 * way, and this module names nothing in {@code az.ideanest.reward}.
 *
 * <p><strong>Deliberately not a tier.</strong> Returning {@code RewardTier} would
 * put another module's entity in this one's application layer, and the boundary
 * test forbids reaching into {@code ..domain..} for exactly that reason. §5.3 asks
 * how many tiers there are and how cheap they are; those are the two answers, and
 * a port that returned more would be a port that has to change when the reward
 * module's internals do.
 */
public interface RewardFacts {

    /**
     * Every tier's price, in the order they are displayed.
     *
     * <p>One list rather than a count and a list, so the two cannot disagree.
     * Empty for a campaign with no tiers, which is a legitimate campaign.
     *
     * <p>{@link BigDecimal} rather than {@code Money}: the checklist compares the
     * amounts against a configured floor and never arithmetically combines them,
     * and every tier is denominated in the campaign's currency by construction —
     * the reward service refuses any other.
     */
    List<BigDecimal> pricesOf(UUID projectId);
}
