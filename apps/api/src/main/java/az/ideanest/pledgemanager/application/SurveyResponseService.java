package az.ideanest.pledgemanager.application;

import az.ideanest.pledge.application.BackedPledges;
import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.domain.QuestionType;
import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyAnswer;
import az.ideanest.pledgemanager.domain.SurveyQuestion;
import az.ideanest.pledgemanager.domain.SurveyResponse;
import az.ideanest.pledgemanager.infrastructure.SurveyAnswerRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyQuestionRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyResponseRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.8's PM-05 and PM-06 (#74): a backer answering, changing their answers until the
 * cut-off, and a creator reading what came back.
 *
 * <h2>What a backer is asked is derived, never trusted</h2>
 *
 * <p>PM-02 makes a question conditional on a reward tier, and which tier a backer
 * chose is on their pledge. So the set of questions this service accepts an answer to
 * is computed from the pledge — a client that submits an answer to a question its
 * backer was not asked is refused, rather than having the answer stored where the
 * export will show it beside answers from people who <em>were</em> asked.
 *
 * <h2>Editing is bounded by a comparison, not by a job</h2>
 *
 * <p>{@code surveys.respond_by} is compared to the clock on every write. Nothing
 * sweeps it, because a job that closed surveys is a job that can be late, and late here
 * means accepting an answer after the creator placed the order.
 *
 * <h2>Two capabilities, and neither is the other</h2>
 *
 * <p>A backer writes by owning the pledge. A creator reads with
 * {@code VIEW_FINANCES} — not {@code PUBLISH_UPDATES}, which is what building and
 * sending a survey needs. A response names a backer and everything they told the
 * campaign about themselves, which is the backer report with more columns; somebody
 * trusted to write an update is not thereby trusted to read that.
 */
@Service
public class SurveyResponseService {

    private final SurveyRepository surveys;
    private final SurveyQuestionRepository questions;
    private final SurveyResponseRepository responses;
    private final SurveyAnswerRepository answers;
    private final BackedPledges pledges;
    private final ProjectAuthorisation projects;
    private final PledgeManagerProperties properties;
    private final Clock clock;

    public SurveyResponseService(
            SurveyRepository surveys,
            SurveyQuestionRepository questions,
            SurveyResponseRepository responses,
            SurveyAnswerRepository answers,
            BackedPledges pledges,
            ProjectAuthorisation projects,
            PledgeManagerProperties properties,
            Clock clock) {

        this.surveys = surveys;
        this.questions = questions;
        this.responses = responses;
        this.answers = answers;
        this.pledges = pledges;
        this.projects = projects;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Every survey this account is being asked, with the questions that apply to them
     * and whatever they have already answered.
     *
     * <p>{@code GET /v1/me/surveys}. Built from the account's backings rather than from
     * a stored recipient list — see {@link SurveySentEvent} for why no such list
     * exists — so a backer who pledged after a survey went out still finds it, which is
     * the behaviour a creator wants and the one that needs no repair job.
     */
    @Transactional(readOnly = true)
    public List<BackerSurvey> mine(UUID backerId) {
        List<BackedPledges.BackedPledge> backings = pledges.ofBacker(backerId);
        if (backings.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<BackedPledges.BackedPledge>> byProject = new LinkedHashMap<>();
        for (BackedPledges.BackedPledge pledge : backings) {
            byProject.computeIfAbsent(pledge.projectId(), key -> new ArrayList<>()).add(pledge);
        }

        List<Survey> sent = surveys.findSentOnProjects(byProject.keySet());
        if (sent.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<SurveyQuestion>> asked = new HashMap<>();
        for (SurveyQuestion question : questions.findBySurveys(sent.stream()
                .map(Survey::getId)
                .toList())) {
            asked.computeIfAbsent(question.getSurveyId(), key -> new ArrayList<>()).add(question);
        }

        Map<SurveyPledge, SurveyResponse> answered = new HashMap<>();
        for (SurveyResponse response :
                responses.findByBackerAndSurveys(backerId, sent.stream().map(Survey::getId).toList())) {
            answered.put(key(response.getSurveyId(), response.getPledgeId()), response);
        }

        Map<UUID, List<SurveyAnswer>> given = new HashMap<>();
        for (SurveyAnswer answer : answers.findByResponses(
                answered.values().stream().map(SurveyResponse::getId).toList())) {
            given.computeIfAbsent(answer.getResponseId(), k -> new ArrayList<>()).add(answer);
        }

        Instant now = clock.instant();
        List<BackerSurvey> result = new ArrayList<>();
        for (Survey survey : sent) {
            for (BackedPledges.BackedPledge pledge : byProject.getOrDefault(survey.getProjectId(), List.of())) {
                List<SurveyQuestion> applicable = asked.getOrDefault(survey.getId(), List.of()).stream()
                        .filter(question -> question.appliesTo(pledge.rewardTierId()))
                        .toList();
                if (applicable.isEmpty()) {
                    // Every question on this survey was conditional on a tier this
                    // backer did not choose. Showing them an empty form would be worse
                    // than not showing it: they would open it, find nothing, and wonder
                    // what they missed.
                    continue;
                }
                SurveyResponse response = answered.get(key(survey.getId(), pledge.pledgeId()));
                result.add(new BackerSurvey(
                        survey,
                        pledge.pledgeId(),
                        applicable,
                        response,
                        response == null ? List.of() : given.getOrDefault(response.getId(), List.of()),
                        survey.isOpen(now)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * PM-05 and PM-06: records or replaces this pledge's answers.
     *
     * <p>The whole set, not a patch. A survey is a form and it is submitted as one; a
     * partial submission would leave the creator unable to tell "they left it blank"
     * from "the client did not send that field", which is precisely the distinction a
     * required question exists to make.
     *
     * @throws PledgeNotBackedException when the pledge is not this account's, or is not
     *     a backing
     * @throws SurveyNotFoundException when the survey is not on that pledge's campaign
     * @throws SurveyNotOpenException when it has not been sent, or the cut-off has
     *     passed
     * @throws AnswerInvalidException when an answer is to a question this backer was
     *     not asked, is the wrong shape for its type, is not one of the offered
     *     options, or is missing from a required question
     */
    @Transactional
    public BackerSurvey respond(UUID surveyId, UUID pledgeId, UUID backerId, Map<UUID, List<String>> submitted) {
        BackedPledges.BackedPledge pledge = pledges.pledge(pledgeId)
                .filter(backing -> backing.backerId().equals(backerId))
                .orElseThrow(() -> new PledgeNotBackedException(pledgeId));

        Survey survey = surveys.findOnProject(pledge.projectId(), surveyId)
                .orElseThrow(() -> new SurveyNotFoundException(surveyId));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!survey.isOpen(now)) {
            throw new SurveyNotOpenException(surveyId, survey.isSent(), survey.getRespondBy());
        }

        List<SurveyQuestion> applicable = questions.findBySurvey(surveyId).stream()
                .filter(question -> question.appliesTo(pledge.rewardTierId()))
                .toList();

        Map<UUID, List<String>> checked = validate(applicable, submitted == null ? Map.of() : submitted);

        SurveyResponse response = responses.findForPledge(surveyId, pledgeId)
                .map(existing -> {
                    existing.resubmitted(now);
                    return existing;
                })
                .orElseGet(() -> responses.save(
                        SurveyResponse.of(Identifiers.newIdentifier(), surveyId, pledgeId, backerId, now)));
        responses.flush();

        // Replaced wholesale, like the questions themselves: an answer left out of the
        // body is one the backer cleared, and merging would leave a stale answer to a
        // question they deliberately blanked.
        answers.deleteAll(answers.findByResponse(response.getId()));
        answers.flush();

        List<SurveyAnswer> stored = new ArrayList<>(checked.size());
        checked.forEach((questionId, value) ->
                stored.add(answers.save(SurveyAnswer.of(response.getId(), questionId, surveyId, value))));

        return new BackerSurvey(survey, pledgeId, applicable, response, List.copyOf(stored), true);
    }

    /**
     * What came back, for the creator.
     *
     * <p>{@code VIEW_FINANCES} — see the class comment. Paged, because a campaign with
     * four thousand backers has four thousand responses and each of them carries every
     * answer they gave.
     */
    @Transactional(readOnly = true)
    public SurveyResponsePage collected(UUID surveyId, UUID accountId, Integer requestedSize) {
        Survey survey = surveys.findById(surveyId).orElseThrow(() -> new SurveyNotFoundException(surveyId));
        // The campaign comes from the survey, so a caller cannot reach one campaign's
        // responses by naming another they happen to have the capability on.
        projects.requireCapability(survey.getProjectId(), accountId, ProjectCapability.VIEW_FINANCES);

        int size = properties.surveys().pageSize(requestedSize);
        List<SurveyResponse> page = responses.page(surveyId, PageRequest.ofSize(size));

        Map<UUID, List<SurveyAnswer>> given = new HashMap<>();
        for (SurveyAnswer answer : answers.findByResponses(page.stream()
                .map(SurveyResponse::getId)
                .toList())) {
            given.computeIfAbsent(answer.getResponseId(), key -> new ArrayList<>()).add(answer);
        }

        return new SurveyResponsePage(
                survey,
                questions.findBySurvey(surveyId),
                page,
                Map.copyOf(given),
                responses.countBySurvey(surveyId));
    }

    /**
     * Checks a submission against the questions this backer was actually asked.
     *
     * <p>Returns the answers that will be stored, which is not the same as what was
     * submitted: an empty answer to an optional question is dropped rather than stored
     * as an empty row, so that "did they answer" has one representation. See
     * {@code SurveyAnswer.replaceWith}.
     */
    private static Map<UUID, List<String>> validate(
            List<SurveyQuestion> applicable, Map<UUID, List<String>> submitted) {

        Map<UUID, SurveyQuestion> asked = new LinkedHashMap<>();
        applicable.forEach(question -> asked.put(question.getId(), question));

        Set<UUID> unknown = new HashSet<>(submitted.keySet());
        unknown.removeAll(asked.keySet());
        if (!unknown.isEmpty()) {
            // Refused rather than ignored. A silently dropped answer is one the backer
            // believes they gave, and the creator manufactures from what they did not.
            throw new AnswerInvalidException(
                    unknown.iterator().next(), "That question is not part of this survey for this pledge.");
        }

        Map<UUID, List<String>> checked = new LinkedHashMap<>();
        for (SurveyQuestion question : applicable) {
            List<String> raw = submitted.get(question.getId());
            List<String> value = raw == null
                    ? List.of()
                    : raw.stream()
                            .map(entry -> entry == null ? "" : entry.trim())
                            .filter(entry -> !entry.isEmpty())
                            .toList();

            if (!question.getType().storesAnswer()) {
                if (!value.isEmpty()) {
                    throw new AnswerInvalidException(
                            question.getId(),
                            "An address is given on the pledge rather than in the survey.");
                }
                continue;
            }

            if (value.isEmpty()) {
                if (question.isRequired()) {
                    throw new AnswerInvalidException(question.getId(), "This question has to be answered.");
                }
                continue;
            }
            if (!question.getType().acceptsMany() && value.size() > 1) {
                throw new AnswerInvalidException(question.getId(), "This question takes one answer.");
            }
            for (String entry : value) {
                if (!question.offers(entry)) {
                    // The words rather than an index — see SurveyAnswer — so an option
                    // the creator has since removed is refused rather than silently
                    // stored as something nobody offered.
                    throw new AnswerInvalidException(question.getId(), "That is not one of the options.");
                }
            }
            if (question.getType() == QuestionType.DATE) {
                try {
                    LocalDate.parse(value.get(0));
                } catch (DateTimeParseException malformed) {
                    throw new AnswerInvalidException(question.getId(), "A date is written as YYYY-MM-DD.");
                }
            }
            checked.put(question.getId(), value);
        }
        return checked;
    }

    /**
     * The key of the "has this pledge answered this survey" lookup.
     *
     * <p>A record rather than a combined {@code UUID}: XOR-ing two identifiers is
     * shorter and collides, and a collision here would show one backer another's
     * answers.
     */
    private record SurveyPledge(UUID surveyId, UUID pledgeId) {
    }

    private static SurveyPledge key(UUID surveyId, UUID pledgeId) {
        return new SurveyPledge(surveyId, pledgeId);
    }
}
