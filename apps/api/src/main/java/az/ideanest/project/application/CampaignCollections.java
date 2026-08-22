package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the payment module is allowed to ask about a campaign it is collecting (#64).
 *
 * <p>The project module's half of the same boundary {@code PledgeCollection} is the
 * pledge module's: three questions, answered with records rather than entities, so
 * that {@code ModuleBoundaryTests}' rule holds and the payment module can be read
 * without {@code Project} in scope.
 *
 * <p><strong>The one interesting method is {@link #beginCollection}, and what is
 * interesting is what it does not do.</strong> It moves the campaign and returns; it
 * does not queue the campaign's pledges, because those are another module's rows.
 * Composing the two into the single transaction they have to share is
 * {@code CollectionOpening}'s job in the payment module — which is the only place that
 * can see both sides, and therefore the only place the requirement can be stated.
 */
@Service
public class CampaignCollections {

    private final ProjectRepository projects;
    private final ProjectTransitionService transitions;

    public CampaignCollections(ProjectRepository projects, ProjectTransitionService transitions) {
        this.projects = projects;
        this.transitions = transitions;
    }

    /**
     * Campaigns §5.1 decided in favour of and whose collection has not started.
     *
     * <p>Read-only and unlocked; each campaign is claimed under its own lock by
     * {@link #beginCollection}. Bounded, because campaigns cluster at midnight and at
     * the ends of months, and "every campaign that closed successfully" is a number this
     * pass does not control.
     */
    @Transactional(readOnly = true)
    public List<UUID> awaitingCollection(int limit) {
        return projects.findAwaitingCollection(PageRequest.ofSize(limit));
    }

    /**
     * §6.1's {@code SUCCESSFUL → COLLECTING}, in the caller's transaction.
     *
     * <p>{@link Propagation#MANDATORY}, unlike {@link ProjectTransitionService#beginCollection}
     * which starts its own. The difference is the caller: this one is composing the state
     * change with the queuing of a campaign's pledges, and the two must be one commit —
     * a campaign that says {@code COLLECTING} with nothing queued is a campaign nobody
     * will ever charge, and queued pledges under a campaign still saying
     * {@code SUCCESSFUL} would be queued again by the next pass.
     *
     * @return the campaign, or empty when another replica opened it first
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CollectingCampaign> beginCollection(UUID projectId, Instant now) {
        return transitions.beginCollection(projectId, now).map(CampaignCollections::describe);
    }

    /**
     * A campaign, for the collection run that is charging one of its pledges.
     *
     * <p>Read on every charge rather than cached per pass, and that is a deliberate cost.
     * It is one primary-key read against a row the page cache is certainly holding, and
     * the alternative — a map built at the start of a pass — would be a creator
     * identifier read once and used for the next several minutes of ledger postings,
     * which is precisely the sort of staleness that is invisible until it credits the
     * wrong account.
     *
     * <p>Empty when the campaign has gone, which nothing does today. The caller treats
     * that as a failed attempt rather than as a reason to charge anyway.
     */
    @Transactional(readOnly = true)
    public Optional<CollectingCampaign> describe(UUID projectId) {
        return projects.findById(projectId).map(CampaignCollections::describe);
    }

    private static CollectingCampaign describe(Project project) {
        return new CollectingCampaign(project.getId(), project.getCreatorId(), project.getCurrency());
    }
}
