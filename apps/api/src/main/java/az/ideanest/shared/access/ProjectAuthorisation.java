package az.ideanest.shared.access;

import java.util.UUID;

/**
 * "May this account do this on this campaign", asked from outside the project module.
 *
 * <p><strong>One method, on purpose.</strong> The alternative this replaces was a
 * predicate per capability on the project module's own service —
 * {@code mayPublishUpdates(accountId, projectId)}, then {@code mayViewFinances}, then
 * one more for every capability any module ever wants. That keeps the enum private at
 * the price of a published surface that grows without bound and that has to be
 * extended, reviewed and released before a caller may ask a question the permission
 * model already answers. Naming the capability as a value costs one enum and answers
 * every question at once.
 *
 * <p><strong>It decides nothing.</strong> The implementation lives in the project
 * module, which owns campaigns, grants, and the rule that a creator holds every
 * capability implicitly. This interface exists so that a caller depends on the
 * question rather than on the class that answers it.
 *
 * <p><strong>Refusals are exceptions, and they are the project module's.</strong>
 * Naming them here would make {@code shared} depend on a module, which is a cycle, so
 * they are described rather than declared:
 *
 * <ul>
 *   <li>{@code project.application.ProjectNotFoundException} — a 404 — when there is no
 *       such campaign <em>and</em> when the caller has no relationship to it at all,
 *       including a revoked grant and an unaccepted invitation. Deliberately the same
 *       answer: a caller who is not party to a campaign is not told it exists.
 *   <li>{@code project.application.CapabilityNotGrantedException} — a 403 — when the
 *       caller holds an active grant on the campaign that does not include the
 *       capability asked for. They were invited and can already see the campaign, so
 *       there is nothing left to hide from them.
 * </ul>
 *
 * <p>Both already have handlers in every module that asks, so a caller moving from a
 * coarse check to a named capability changes which requests are refused and not how a
 * refusal looks.
 */
public interface ProjectAuthorisation {

    /**
     * Refuses unless this account holds this capability on this campaign.
     *
     * <p>Answers nothing on success. A caller that needs the campaign back has to be
     * inside the project module, or ask for something the project module publishes —
     * which is what stops another module's entity from leaking through an
     * authorisation call.
     *
     * @param projectId the campaign the request is about
     * @param accountId the authenticated caller, never a value taken from a request
     *     body
     * @param capability the one this request needs. A caller that knows which it needs
     *     is expected to say so: a grant of {@code EDIT_REWARDS} is not a grant of
     *     {@code PUBLISH_UPDATES}, and a single coarse check makes those one grant
     */
    void requireCapability(UUID projectId, UUID accountId, ProjectCapability capability);
}
