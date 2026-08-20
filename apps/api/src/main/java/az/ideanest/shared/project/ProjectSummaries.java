package az.ideanest.shared.project;

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
}
