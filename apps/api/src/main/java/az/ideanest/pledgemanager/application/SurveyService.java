package az.ideanest.pledgemanager.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.pledge.application.BackedPledges;
import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.domain.QuestionType;
import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyContentInvalidException;
import az.ideanest.pledgemanager.domain.SurveyQuestion;
import az.ideanest.pledgemanager.infrastructure.SurveyQuestionRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyResponseRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.shared.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.8's PM-01 to PM-04 (#73, #74): building a survey, and sending it.
 *
 * <h2>{@code PUBLISH_UPDATES}, not {@code EDIT_REWARDS}</h2>
 *
 * <p>A survey is the campaign speaking to its backers — it arrives in their inbox and
 * in their email, in the campaign's name — which is the same authority a numbered
 * update needs and the reason #236 made that capability separate from "may edit this
 * campaign at all".
 *
 * <p>Reading the <em>responses</em> is a different question and needs
 * {@code VIEW_FINANCES}: a response names a backer and what they chose, which is the
 * backer report with extra columns. That check lives in
 * {@link SurveyResponseService}.
 *
 * <h2>The questions freeze at the send, and the rest does not</h2>
 *
 * <p>Editing a question after four hundred people answered it changes what they were
 * asked without changing what they said — and V35's foreign key from
 * {@code survey_answers} refuses the delete underneath it, so the alternative to
 * refusing here is a constraint violation the creator cannot read.
 *
 * <p>What stays editable is the covering note and the cut-off. The first is prose
 * nobody answered; the second is the thing creators most often need to change, and
 * refusing it would leave them re-sending the whole survey to extend a deadline by a
 * week.
 *
 * <h2>Sending is one transaction: the survey, the count, and one event</h2>
 *
 * <p>The audience is resolved here to freeze {@code sent_to} and tell the creator what
 * they reached, and resolved again in the notification module to write the rows —
 * exactly as {@code CampaignMessageService} does, and for the reasons that class
 * argues about why the two are allowed to differ.
 */
@Service
public class SurveyService {

    private static final Logger log = LoggerFactory.getLogger(SurveyService.class);

    private final SurveyRepository surveys;
    private final SurveyQuestionRepository questions;
    private final SurveyResponseRepository responses;
    private final BackedPledges pledges;
    private final ProjectAuthorisation projects;
    private final Outbox outbox;
    private final AuditLog audit;
    private final PledgeManagerProperties properties;
    private final Clock clock;

    public SurveyService(
            SurveyRepository surveys,
            SurveyQuestionRepository questions,
            SurveyResponseRepository responses,
            BackedPledges pledges,
            ProjectAuthorisation projects,
            Outbox outbox,
            AuditLog audit,
            PledgeManagerProperties properties,
            Clock clock) {

        this.surveys = surveys;
        this.questions = questions;
        this.responses = responses;
        this.pledges = pledges;
        this.projects = projects;
        this.outbox = outbox;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** The campaign's surveys, newest first, drafts included. */
    @Transactional(readOnly = true)
    public List<SurveyDetail> list(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.PUBLISH_UPDATES);
        return surveys.findByProject(projectId).stream()
                .map(survey -> detailOf(survey, questions.findBySurvey(survey.getId())))
                .toList();
    }

    /**
     * One survey with its questions.
     *
     * <p>Addressed by survey rather than by campaign, following §10.2 — the identifier
     * is unique, and a client holding one should not have to remember which campaign it
     * came from. The campaign is loaded from the survey and the capability is checked
     * against <em>that</em>, so the authorisation is no weaker than a nested path would
     * make it; what it is not is a second identifier a client can get wrong.
     */
    @Transactional(readOnly = true)
    public SurveyDetail read(UUID surveyId, UUID accountId) {
        Survey survey = requireEditable(surveyId, accountId);
        return detailOf(survey, questions.findBySurvey(surveyId));
    }

    /**
     * Creates a draft survey with its questions.
     *
     * @throws SurveyContentInvalidException when the title, a prompt, or a question's
     *     options are not something the platform will store
     * @throws TooManyQuestionsException above {@code ideanest.pledge-manager.surveys.max-questions}
     */
    @Transactional
    public SurveyDetail create(UUID projectId, UUID accountId, SurveyDefinition definition) {
        projects.requireCapability(projectId, accountId, ProjectCapability.PUBLISH_UPDATES);

        Survey survey = surveys.save(Survey.draft(
                Identifiers.newIdentifier(),
                projectId,
                accountId,
                definition.title(),
                definition.message(),
                definition.respondBy()));

        return detailOf(survey, replaceQuestions(survey, definition.questions()));
    }

    /**
     * Rewrites a survey.
     *
     * <p>The whole thing, questions included, for {@code RewardService}'s reason about
     * rate tables: a question left out of the body is one the creator deleted, and
     * merging would leave a question on the form that they believe they removed.
     *
     * @throws SurveyAlreadySentException when the questions differ and the survey has
     *     been sent. The note and the cut-off are applied in that case; the questions
     *     are not, and the refusal says so rather than silently applying half
     */
    @Transactional
    public SurveyDetail update(UUID surveyId, UUID accountId, SurveyDefinition definition) {
        Survey survey = requireEditable(surveyId, accountId);

        List<SurveyQuestion> existing = questions.findBySurvey(surveyId);
        if (survey.isSent()) {
            // Refused before anything is written, so a creator who tried to edit a
            // sent survey does not find its title changed and its questions not.
            if (definition.questions() != null && !describesTheSameQuestions(existing, definition.questions())) {
                throw new SurveyAlreadySentException(surveyId);
            }
            survey.describe(definition.title(), definition.message(), definition.respondBy());
            return detailOf(survey, existing);
        }

        survey.describe(definition.title(), definition.message(), definition.respondBy());
        return detailOf(survey, replaceQuestions(survey, definition.questions()));
    }

    /**
     * Deletes a draft.
     *
     * <p>Only a draft. A sent survey is a message several thousand people received and
     * a set of answers they gave; deleting it would discard the answers with it, which
     * V35's foreign key refuses anyway.
     */
    @Transactional
    public void delete(UUID surveyId, UUID accountId) {
        Survey survey = requireEditable(surveyId, accountId);
        if (survey.isSent()) {
            throw new SurveyAlreadySentException(surveyId);
        }
        questions.deleteAll(questions.findBySurvey(surveyId));
        surveys.delete(survey);
    }

    /**
     * PM-04: sends the survey to the campaign's backers.
     *
     * <p>One transaction: the survey is marked sent with a frozen recipient count, and
     * one outbox event announces it. The notification module resolves the audience
     * again when it delivers — see {@link SurveySentEvent}.
     *
     * @throws SurveyAlreadySentException when it has already gone. Re-sending is a new
     *     survey, because "sent" is a fact about a message people already received
     * @throws SurveyHasNoQuestionsException when there is nothing to ask
     */
    @Transactional
    public SurveyDetail send(UUID surveyId, UUID accountId) {
        Survey survey = requireEditable(surveyId, accountId);
        UUID projectId = survey.getProjectId();

        if (survey.isSent()) {
            throw new SurveyAlreadySentException(surveyId);
        }
        List<SurveyQuestion> asked = questions.findBySurvey(surveyId);
        if (asked.isEmpty()) {
            throw new SurveyHasNoQuestionsException(surveyId);
        }

        int ceiling = properties.surveys().maxRecipients();
        // One more than the ceiling, so "there were more" is a fact rather than an
        // inference from a full page — the trick CampaignMessageService uses, and what
        // lets the truncation be reported honestly.
        List<BackedPledges.BackedPledge> audience = pledges.onProject(projectId, ceiling + 1);
        boolean truncated = audience.size() > ceiling;
        int reached = truncated ? ceiling : audience.size();

        if (truncated) {
            log.error(
                    "Campaign {} has more than {} backers; survey {} reaches the first {} and the rest are not asked",
                    projectId,
                    ceiling,
                    surveyId,
                    ceiling);
        }

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        survey.sent(at, reached);
        surveys.flush();

        UUID eventId = outbox.record(
                SurveySentEvent.AGGREGATE_TYPE,
                projectId,
                SurveySentEvent.EVENT_TYPE,
                SurveySentEvent.of(survey, truncated, at));

        audit.record(
                AuditAction.PROJECT_SURVEY_SENT,
                projectId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "surveyId=" + surveyId + "; questions=" + asked.size() + "; recipients=" + reached
                        + "; truncated=" + truncated);

        log.info("Campaign {} sent survey {} to {} backers; outbox event {}", projectId, surveyId, reached, eventId);
        return detailOf(survey, asked);
    }

    /**
     * Makes the survey ask exactly these questions, in this order.
     *
     * <p><strong>Delete then insert, and not a diff.</strong> The opposite of what
     * {@code RewardService} does with rate tables, and the difference is what identity
     * means here: a rate is identified by its destination, so "the rate for Germany"
     * survives an edit, while a question has no natural key — a creator who rewrites
     * the third question has written a different question, and matching it to the old
     * one by position would silently attach it to any answers that existed. Since this
     * only ever runs on a draft, there are no answers to attach, and the simpler
     * operation is the one that cannot be wrong.
     *
     * <p>The positions are dense and rewritten every time, which is why V35 makes the
     * uniqueness deferrable: the delete and the inserts are one flush, and a partial
     * state inside it would collide.
     */
    private List<SurveyQuestion> replaceQuestions(Survey survey, List<QuestionDefinition> definitions) {
        List<QuestionDefinition> requested = definitions == null ? List.of() : definitions;
        int ceiling = properties.surveys().maxQuestions();
        if (requested.size() > ceiling) {
            throw new TooManyQuestionsException(ceiling, requested.size());
        }

        questions.deleteAll(questions.findBySurvey(survey.getId()));
        questions.flush();

        List<SurveyQuestion> result = new ArrayList<>(requested.size());
        int position = 0;
        for (QuestionDefinition definition : requested) {
            if (definition == null) {
                throw new SurveyContentInvalidException("questions", "A question has a prompt and a type.");
            }
            result.add(questions.save(SurveyQuestion.of(
                    Identifiers.newIdentifier(),
                    survey.getId(),
                    survey.getProjectId(),
                    position++,
                    definition.prompt(),
                    definition.helpText(),
                    typeOf(definition.type()),
                    definition.required(),
                    definition.choices(),
                    definition.rewardTierId())));
        }
        return List.copyOf(result);
    }

    /**
     * Whether a body describes the questions the survey already has.
     *
     * <p>Asked so that a client which re-sends the whole survey in order to change the
     * cut-off is not refused for having included the questions it read a moment ago.
     * Compared on what a backer sees — prompt, type, options, condition, order — rather
     * than on identifiers, because a client is not required to have kept them.
     */
    private static boolean describesTheSameQuestions(
            List<SurveyQuestion> existing, List<QuestionDefinition> requested) {

        if (existing.size() != requested.size()) {
            return false;
        }
        for (int index = 0; index < existing.size(); index++) {
            SurveyQuestion stored = existing.get(index);
            QuestionDefinition asked = requested.get(index);
            if (asked == null
                    || !stored.getPrompt().equals(asked.prompt() == null ? null : asked.prompt().trim())
                    || stored.getType() != typeOf(asked.type())
                    || stored.isRequired() != asked.required()
                    || !stored.getChoices().equals(asked.choices() == null ? List.of() : asked.choices())
                    || !java.util.Objects.equals(stored.getRewardTierId(), asked.rewardTierId())) {
                return false;
            }
        }
        return true;
    }

    /** The wire name as the enum, refused by name rather than as a bare 400. */
    private static QuestionType typeOf(String type) {
        try {
            return QuestionType.valueOf(type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new SurveyContentInvalidException(
                    "type",
                    "A question is one of " + java.util.Arrays.toString(QuestionType.values()) + ".");
        }
    }

    /**
     * The survey, and the capability to work on it.
     *
     * <p>The campaign comes from the survey, so a caller cannot name a campaign they
     * <em>do</em> have the capability on in order to reach a survey belonging to one
     * they do not.
     */
    private Survey requireEditable(UUID surveyId, UUID accountId) {
        Survey survey = surveys.findById(surveyId).orElseThrow(() -> new SurveyNotFoundException(surveyId));
        projects.requireCapability(survey.getProjectId(), accountId, ProjectCapability.PUBLISH_UPDATES);
        return survey;
    }

    private SurveyDetail detailOf(Survey survey, List<SurveyQuestion> asked) {
        // The response count comes back with the survey because it is the number a
        // creator is actually looking for, and fetching it separately would make every
        // list screen n+1 queries deep.
        return new SurveyDetail(survey, asked, survey.isSent() ? responses.countBySurvey(survey.getId()) : 0L);
    }
}
