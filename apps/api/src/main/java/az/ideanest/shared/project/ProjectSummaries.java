package az.ideanest.shared.project;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "What is this campaign called", asked from outside the module that owns it.
 *
 * <p><strong>One method, for the reason {@code ProjectAuthorisation} gives in full.</strong>
 * Naming the facts as a record costs one type and answers every question at once, where a
 * method per fact is a published surface that grows without bound.
 *
 * <p><strong>It decides nothing.</strong> The implementation lives in the project module,
 * which owns {@code projects}, and this interface exists so that the notification module
 * depends on the question rather than on that table.
 *
 * <h2>Every state, and that is the point</h2>
 *
 * <p>{@code PublicProjects} refuses any campaign in a state §6.1 does not publish, and it is
 * right to: it serves a public page. This is not that. A notification is addressed to
 * somebody who is already party to the campaign — its creator, or somebody who backed it —
 * and the messages most likely to concern a campaign that is no longer public are exactly
 * the ones about it being suspended, cancelled or unsuccessful. An implementation that
 * filtered by state would therefore drop the title from the messages that need it most,
 * silently, and the reader would get "this campaign" on the one message where knowing which
 * campaign is the whole content.
 *
 * <p>So the answer is empty only when there is no such row. Callers must handle that: an
 * event about a campaign that has since been removed must not be able to fail a dispatch
 * that other modules share, which is the argument {@code ProjectAudiences} makes about a
 * campaign that does not exist and {@code NotificationFanOut} makes about a recipient who is
 * not an account.
 *
 * <h2>Why a caller asks at translation time and not at send time</h2>
 *
 * <p>Rendering the current title when the message goes out would be a different fact from
 * the one the event described. {@code notifications.params} is documented as what a template
 * will need and cannot look up, and a title stored there is <em>the title as it was</em> —
 * so a campaign renamed between the pledge and the digest still says, in the confirmation,
 * what the backer thought they were backing. Looking it up in the sender also puts a read of
 * another module's rows inside the delivery loop, on every attempt, for every recipient of a
 * fan-out that may have thousands.
 */
public interface ProjectSummaries {

    /**
     * The campaign's name and public path, whatever state it is in.
     *
     * @param projectId the campaign. <strong>Null is an empty answer, not an error</strong>,
     *     for the reason the class comment gives: the caller is translating an event shared
     *     with other modules and must not be able to fail their writes over a field it
     *     cannot use
     * @return the summary, or empty when there is no such campaign. Never null
     */
    Optional<ProjectSummary> summaryOf(UUID projectId);

    /**
     * The same, for a page of campaigns at once.
     *
     * <p><strong>A second method rather than a loop at the call site, and #90 is what made it
     * necessary.</strong> {@code GET /v1/me/saved} is a page of twenty campaigns the community
     * module holds identifiers for and nothing else; asking one at a time is twenty round
     * trips per page view, on a read a signed-in visitor performs often. The one-at-a-time
     * method stays because event translation genuinely asks about one campaign, and expressing
     * it as a batch of one there would read as though it might be several.
     *
     * <p><strong>Absent campaigns are absent from the answer.</strong> The result is not
     * positional and is not padded — a caller that needs to know which identifiers resolved
     * compares the sets, which is the same contract {@code summaryOf} states for one. It is
     * not an error for the same reason: a saved campaign that has since been hard deleted is
     * an ordinary thing to find, and a page of saved campaigns must not fail because one of
     * them is gone.
     *
     * @param projectIds the campaigns. Null and empty are both an empty answer
     * @return one summary per campaign that exists, in no promised order. Never null,
     *     possibly shorter than what was asked for, never longer and never with a duplicate
     */
    List<ProjectSummary> summariesOf(Collection<UUID> projectIds);
}
