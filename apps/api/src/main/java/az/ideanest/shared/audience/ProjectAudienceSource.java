package az.ideanest.shared.audience;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A module's answer, for the audiences whose rows it owns.
 *
 * <p><strong>This interface exists because #245's second half has a different owner from its
 * first.</strong> When {@code BACKERS} was the only audience, one interface with one
 * implementation said everything: the pledge module owned {@code pledges} and therefore owned
 * the question. #90 puts {@code saves} and {@code follows} in the community module, so
 * {@code SAVERS} and {@code FOLLOWERS} are answered from somewhere else — and one interface
 * with two implementations is not a port, it is an injection failure.
 *
 * <p>The alternatives were both worse. One module implementing all three would have to read
 * another module's tables, which is the coupling {@code ModuleBoundaryTests} exists to prevent
 * and the reason this package exists at all. A method per audience on {@link ProjectAudiences}
 * is the published surface that grows without bound, argued against on that interface and on
 * {@code ProjectAuthorisation} before it.
 *
 * <p>So the published question stays one method on one interface, and this is the seam behind
 * it: each module declares which audiences it answers, and {@link RoutedProjectAudiences} sends
 * each question to the module that claimed it.
 *
 * <p><strong>It deliberately does not extend {@link ProjectAudiences}.</strong> If it did,
 * every source would also be a candidate for injection wherever the question is asked, and
 * "which of these three beans did the notification module get" would be answered by a
 * {@code @Primary} annotation somebody can delete. Two interfaces means there is exactly one
 * bean of the type callers name, and no annotation is load-bearing.
 *
 * <h2>What a claim means</h2>
 *
 * <p>Claiming an audience is a promise to answer it for every campaign, including with an empty
 * list. It is not a hint and it is not a preference: {@link RoutedProjectAudiences} refuses to
 * start when an audience has no claimant or more than one, which is what turns
 * {@link ProjectAudience}'s standing rule — "adding a constant is a decision and a released
 * implementation, in that order" — from a comment into something the application checks.
 */
public interface ProjectAudienceSource {

    /**
     * The audiences this module answers.
     *
     * <p>Never empty: a source that claims nothing is a bean that does nothing, and the more
     * likely explanation for one is that somebody deleted the last constant it answered and
     * left the class behind.
     */
    Set<ProjectAudience> answers();

    /**
     * The members of one of the audiences this source claimed.
     *
     * <p>The contract is {@link ProjectAudiences#membersOf}'s, unchanged — bounded, distinct,
     * stably ordered, an empty list for a campaign that does not exist, and a refusal when the
     * limit is not positive. The one addition is that an implementation may assume the audience
     * is one it claimed: the router never passes another, and an exhaustive {@code switch} over
     * the constants it handles is the right shape for the body.
     */
    List<UUID> membersOf(UUID projectId, ProjectAudience audience, int limit);
}
