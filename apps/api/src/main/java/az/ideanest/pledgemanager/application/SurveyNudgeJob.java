package az.ideanest.pledgemanager.application;

import az.ideanest.pledge.application.BackedPledges;
import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.domain.Survey;
import az.ideanest.pledgemanager.domain.SurveyNudge;
import az.ideanest.pledgemanager.infrastructure.SurveyNudgeRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyRepository;
import az.ideanest.pledgemanager.infrastructure.SurveyResponseRepository;
import az.ideanest.shared.jobs.ScheduledJob;
import az.ideanest.shared.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * §8.4's {@code survey-nudge} and §4.8's PM-24: chasing the backers who have not
 * answered.
 *
 * <h2>The row is the claim</h2>
 *
 * <p>A {@code survey_nudges} row is written in the same transaction as the outbox
 * event that reminds somebody, exactly as {@code deadline_notices} is. A crash leaves
 * them either unchased and unclaimed or chased and claimed, and never chased twice.
 *
 * <p>Without it the sweep's question — "who has not answered" — stays true for as long
 * as they have not, so every daily pass would be another email to the same people. That
 * is not a hypothetical: it is what a survey reminder <em>is</em> if nothing records
 * that it went.
 *
 * <h2>Three reminders, a week apart, and then nothing</h2>
 *
 * <p>Both numbers are configuration, and both are argued in
 * {@code PledgeManagerProperties.Surveys}. The interval is measured from the last
 * contact rather than from the send, so a backer who was reminded late is not reminded
 * again the next morning to catch up.
 *
 * <p>Setting {@code nudge-attempts} to zero switches reminders off, which is a
 * configuration a deployment might reasonably want and is why the property is not
 * validated as positive.
 *
 * <h2>Daily, and bounded</h2>
 *
 * <p>Daily because a reminder is judged on nothing about promptness — §8.4 already says
 * so — and bounded per pass because a backlog must not become one run that overlaps its
 * own next tick. Surveys are worked through oldest-sent first, so what is left over is
 * always the least overdue part of it.
 */
@Component
public class SurveyNudgeJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(SurveyNudgeJob.class);

    /**
     * How many reminders one pass will send in total.
     *
     * <p>A constant rather than a property, unlike the two numbers above: it is a
     * bound on the job's own footprint rather than a product decision, and an operator
     * who needs to change it is an operator who should be told why the sweep is behind.
     */
    private static final int PER_PASS = 500;

    /**
     * How long after the send the first reminder may go.
     *
     * <p>The same as the interval between reminders, and deliberately: a survey that
     * arrived this morning has not been ignored, it has been received. Chasing it
     * within a day is what makes a reminder read as nagging.
     */
    private final PledgeManagerProperties properties;

    private final SurveyRepository surveys;
    private final SurveyResponseRepository responses;
    private final SurveyNudgeRepository nudges;
    private final BackedPledges pledges;
    private final Outbox outbox;
    private final Clock clock;

    public SurveyNudgeJob(
            SurveyRepository surveys,
            SurveyResponseRepository responses,
            SurveyNudgeRepository nudges,
            BackedPledges pledges,
            Outbox outbox,
            PledgeManagerProperties properties,
            Clock clock) {

        this.surveys = surveys;
        this.responses = responses;
        this.nudges = nudges;
        this.pledges = pledges;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "survey-nudge";
    }

    /**
     * A property so the test profile can set it to {@code -} and drive
     * {@link #chaseNonResponders()} directly — a timer firing in the background of a
     * suite acts on the very rows a test is about to assert on.
     */
    @Override
    public String schedule() {
        return properties.surveys().nudgeSchedule();
    }

    @Override
    public void run() {
        chaseNonResponders();
    }

    /**
     * One pass.
     *
     * @return how many reminders it sent
     */
    @Transactional
    public int chaseNonResponders() {
        PledgeManagerProperties.Surveys limits = properties.surveys();
        if (limits.nudgeAttempts() < 1) {
            return 0;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant sentBefore = now.minus(limits.nudgeInterval());

        int sent = 0;
        for (Survey survey : surveys.findOpenForReminder(now, sentBefore)) {
            if (sent >= PER_PASS) {
                log.info(
                        "survey-nudge reached its per-pass bound of {}; the remainder waits for the next run",
                        PER_PASS);
                break;
            }
            sent += chase(survey, limits, now, sentBefore, PER_PASS - sent);
        }
        return sent;
    }

    /** The non-responders on one survey, up to {@code budget} of them. */
    private int chase(
            Survey survey, PledgeManagerProperties.Surveys limits, Instant now, Instant sentBefore, int budget) {

        Set<UUID> answered = new HashSet<>(responses.findAnsweredPledges(survey.getId()));

        Map<UUID, int[]> attempts = new HashMap<>();
        Map<UUID, Instant> lastContact = new HashMap<>();
        for (Object[] row : nudges.latestPerPledge(survey.getId())) {
            UUID pledgeId = (UUID) row[0];
            attempts.put(pledgeId, new int[] {((Number) row[1]).intValue()});
            lastContact.put(pledgeId, (Instant) row[2]);
        }

        int sent = 0;
        List<BackedPledges.BackedPledge> audience =
                pledges.onProject(survey.getProjectId(), limits.maxRecipients());

        for (BackedPledges.BackedPledge pledge : audience) {
            if (sent >= budget) {
                break;
            }
            if (answered.contains(pledge.pledgeId())) {
                continue;
            }
            int[] attempt = attempts.get(pledge.pledgeId());
            int next = attempt == null ? 1 : attempt[0] + 1;
            if (next > limits.nudgeAttempts()) {
                continue;
            }
            // Measured from the last contact rather than from the send, so a backer
            // reminded late is not reminded again the next morning to catch up.
            Instant since = lastContact.getOrDefault(pledge.pledgeId(), survey.getSentAt());
            if (since != null && since.isAfter(sentBefore)) {
                continue;
            }

            // The row and the event, one transaction. See the class comment.
            nudges.save(SurveyNudge.of(survey.getId(), pledge.pledgeId(), next, now));
            outbox.record(
                    SurveyNudgedEvent.AGGREGATE_TYPE,
                    survey.getProjectId(),
                    SurveyNudgedEvent.EVENT_TYPE,
                    new SurveyNudgedEvent(
                            survey.getId(),
                            survey.getProjectId(),
                            pledge.pledgeId(),
                            pledge.backerId(),
                            survey.getTitle(),
                            survey.getRespondBy(),
                            next,
                            now));
            sent++;
        }

        if (sent > 0) {
            log.info("survey-nudge chased {} non-responders on survey {}", sent, survey.getId());
        }
        return sent;
    }
}
