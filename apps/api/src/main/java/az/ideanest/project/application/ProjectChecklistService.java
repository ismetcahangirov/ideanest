package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.domain.ActorRole;
import az.ideanest.project.domain.CampaignCompleteness;
import az.ideanest.project.domain.ChecklistResult;
import az.ideanest.project.domain.Project;
import az.ideanest.project.domain.StoryDocuments;
import az.ideanest.project.domain.SubmissionChecklist;
import az.ideanest.project.domain.SubmissionLimits;
import az.ideanest.project.infrastructure.ProjectStateTransitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers what {@link SubmissionChecklist} needs and hands it the campaign.
 *
 * <p><strong>Gathering here, judging there.</strong> The rules are a pure type in
 * {@code domain} with no Spring and no database, so that the whole of §5.3 can be
 * asserted in a plain unit test against record literals. Everything that makes
 * that possible is in this file: loading the campaign, authorising the caller,
 * parsing the stored story, and asking another module about reward tiers. None of
 * it is a rule, and none of the rules are here.
 *
 * <p><strong>Two callers, one evaluation.</strong> {@link #reviewOf} serves
 * {@code GET /v1/projects/{id}/checklist} and {@link #requireSubmittable} serves
 * {@code ProjectTransitionService.submit}. Both go through {@link #evaluate},
 * which is the arrangement {@code ProjectTransitionService}'s class comment asks
 * for: the advice a creator reads and the rule that refuses their submission
 * cannot disagree, because there is one implementation of the rules and one path
 * to it.
 *
 * <p>Authorised exactly as the rest of the editor is — {@link ProjectAccess}
 * decides, here as everywhere. The checklist is a read of the campaign's contents,
 * so it takes the same coarse "may this account work on this campaign" check that
 * {@code GET /v1/projects/{id}/edit} takes. Submitting is a narrower question and
 * is asked with {@code SUBMIT_FOR_REVIEW} by the caller that performs it.
 */
@Service
public class ProjectChecklistService {

    /**
     * A mapper for reading stored documents back into trees.
     *
     * <p>Its own instance rather than the application's injected one, for the
     * reason {@code StoryVersionService} gives: this reads text PostgreSQL has
     * already validated as JSON, and whether a campaign may be submitted must not
     * depend on how the HTTP layer's mapper happens to be configured.
     */
    private static final ObjectMapper JSON_TREES = new ObjectMapper();

    private final ProjectAccess access;
    private final ProjectStateTransitionRepository transitions;
    private final RewardFacts rewards;
    private final SubmissionLimits limits;

    public ProjectChecklistService(
            ProjectAccess access,
            ProjectStateTransitionRepository transitions,
            RewardFacts rewards,
            ProjectProperties properties) {

        this.access = access;
        this.transitions = transitions;
        this.rewards = rewards;
        // Converted once, at construction, so that a deployment with inverted goal
        // bounds fails at start-up where an operator sees it rather than on the
        // first creator to open the review tab. SubmissionLimits is where that rule
        // lives; this is what makes it run early.
        this.limits = new SubmissionLimits(
                properties.submission().goalMinimum(),
                properties.submission().goalMaximum(),
                properties.submission().rewardPriceMinimum());
    }

    /**
     * The review screen: the campaign, its checklist, and the last moderation
     * decision.
     *
     * <p>All three in one transaction — see {@link CampaignReview} for why they
     * must not be read separately.
     */
    @Transactional(readOnly = true)
    public CampaignReview reviewOf(UUID projectId, UUID accountId) {
        Project project = access.requireEditable(projectId, accountId);
        return new CampaignReview(project, evaluate(project), moderationOf(project));
    }

    /**
     * §5.3, applied to a campaign that has already been loaded and authorised.
     *
     * <p>Takes the campaign rather than an identifier because its two callers have
     * one already, and one of them holds it under a row lock it must not drop: the
     * submission reads the state, decides the edge, checks completeness, and writes
     * an audit row claiming all of that happened together. A method that re-loaded
     * the campaign would evaluate a second, unlocked copy of it.
     */
    public ChecklistResult evaluate(Project project) {
        JsonNode story = storyOf(project);

        CampaignCompleteness campaign = new CampaignCompleteness(
                project.getTitle(),
                project.getBlurb(),
                project.getCategoryId(),
                project.getSubcategoryId(),
                project.getCoverImage(),
                project.getGoalAmount(),
                project.getCurrency(),
                project.getDurationDays(),
                project.getScheduledLaunchAt(),
                StoryDocuments.characterCount(story),
                StoryDocuments.mediaCount(story),
                project.getRisks(),
                rewards.pricesOf(project.getId()));

        return SubmissionChecklist.evaluate(campaign, limits);
    }

    /**
     * Refuses a submission §5.3 does not permit.
     *
     * <p>The enforcement half. See {@link ProjectNotSubmittableException}: the
     * checklist endpoint is advice given to a client that may be old, offline, or
     * somebody else's, and this is the same rules on the write itself.
     *
     * @throws ProjectNotSubmittableException naming every unmet blocking
     *     requirement and the editor section that fixes each
     */
    public void requireSubmittable(Project project) {
        ChecklistResult checklist = evaluate(project);
        if (!checklist.isSubmittable()) {
            throw new ProjectNotSubmittableException(checklist.unmetBlocking());
        }
    }

    /**
     * The last thing platform staff said about this campaign, or null if they
     * never have.
     *
     * <p>Read here rather than put on {@code ProjectEdit}. That projection is the
     * response of nine endpoints including every autosave, so a moderator's note
     * there would be an extra query on {@code project_state_transitions} several
     * times a minute while somebody types, for a value only this screen renders —
     * and it is not a field of the campaign in any case. A separate endpoint was
     * the other option and is worse: the note and the state have to be read
     * together, and two requests can return a note from a decision the second one
     * shows as already superseded.
     */
    private ModerationOutcome moderationOf(Project project) {
        return transitions
                .findFirstByProjectIdAndActorRoleOrderByCreatedAtDesc(project.getId(), ActorRole.MODERATOR)
                .map(decision -> ModerationOutcome.of(decision, project.getState()))
                .orElse(null);
    }

    /**
     * The stored story as a tree, or null when there is none or it cannot be read.
     *
     * <p>Null rather than an exception, exactly as {@code StoryVersionService}
     * treats the same column. Both counts that read it are tolerant of a malformed
     * document by design, and a checklist that refused to render because one row
     * was written by an earlier version of the schema would be a worse failure than
     * a story reported as zero characters — which is, in any case, the honest
     * answer for a document nothing can read.
     */
    private static JsonNode storyOf(Project project) {
        if (project.getStory() == null) {
            return null;
        }
        try {
            return JSON_TREES.readTree(project.getStory());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
