package az.ideanest.project.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * §5.1's all-or-nothing rule, as a value.
 *
 * <p><strong>A comparison, in one place, with no clock and no database.</strong> The
 * rule is four lines of English in §5.1 and one line of arithmetic here, and putting it
 * in the domain rather than inside the sweep that applies it is the same argument
 * {@link ProjectStateMachine} makes about the edges: the rule that decides whether ten
 * thousand people are charged should be checkable by a unit test that starts nothing.
 *
 * <p><strong>What is deliberately not part of the decision.</strong>
 *
 * <ul>
 *   <li><strong>The deadline.</strong> §5.1 has two conditions and this type answers
 *       only the second. Whether the campaign has reached its deadline is
 *       {@code CampaignFinalizer}'s question, because it is the one the sweep's query
 *       already asked and the one a lock has to be held to answer safely.
 *   <li><strong>Fees.</strong> §5.2 charges a platform fee on the amount raised by a
 *       successful campaign, and none at all on an unsuccessful one. That is arithmetic
 *       on the outcome, not an input to it: a campaign does not fail because of what it
 *       would have cost.
 *   <li><strong>Whether anything was collected.</strong> Nothing has been collected at
 *       this point and nothing needs to have been. The outcome is decided on what was
 *       pledged, and V29 says at length why a later collection failure must not be able
 *       to revisit it.
 * </ul>
 */
public enum CampaignOutcome {

    /** §5.1: the total reached the goal. Every confirmed pledge is collected. */
    SUCCESSFUL(ProjectState.SUCCESSFUL),

    /**
     * §5.1: the total did not reach the goal. Nothing is collected, no fee of any kind
     * is charged, and the stored cards are purged within thirty days.
     */
    UNSUCCESSFUL(ProjectState.UNSUCCESSFUL);

    private final ProjectState state;

    CampaignOutcome(ProjectState state) {
        this.state = state;
    }

    /**
     * Which of §5.1's two branches this campaign falls into.
     *
     * <p><strong>{@code >=}, and the boundary is the whole point.</strong> A campaign
     * that raised exactly its goal succeeded. Written as {@code >} it would fail, and it
     * would fail for the one creator who hit the number exactly — the case nobody tests
     * by hand and everybody notices.
     *
     * <p><strong>{@link BigDecimal#compareTo} rather than {@link BigDecimal#equals}</strong>,
     * which is the standing rule for money on this platform: {@code 1000.00} and
     * {@code 1000.000} are the same amount and are not equal objects. Nothing here would
     * read differently with {@code equals}, because nothing here calls it — which is
     * precisely why it is worth saying, since the next person to touch this file might.
     *
     * @param pledged what the campaign raised, which is never null: {@code projects
     *     .pledged_amount} is {@code NOT NULL DEFAULT 0} since V6, so a campaign nobody
     *     backed compares zero against its goal and fails, as it should
     * @param goal what it had to raise. Not null by the time a campaign is live —
     *     {@code ProjectTransitionService.requireLaunchable} refuses the edge without one
     *     — so a null here is a campaign that reached {@code LIVE} by some path that is
     *     not the service, and answering it as "unsuccessful" would silently close
     *     somebody's campaign over a bug in ours
     */
    public static CampaignOutcome of(BigDecimal pledged, BigDecimal goal) {
        Objects.requireNonNull(pledged, "A campaign's pledged total is never null");
        Objects.requireNonNull(goal, "A live campaign has a goal; §5.1 cannot be applied without one");

        return pledged.compareTo(goal) >= 0 ? SUCCESSFUL : UNSUCCESSFUL;
    }

    /** The state §6.1 gives this outcome. */
    public ProjectState state() {
        return state;
    }
}
