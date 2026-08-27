package az.ideanest.risk.application;

import az.ideanest.risk.RiskProperties;
import az.ideanest.risk.domain.RiskAssessment;
import az.ideanest.risk.domain.RiskDecision;
import az.ideanest.risk.infrastructure.RiskAssessmentRepository;
import az.ideanest.risk.infrastructure.RiskFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * §17.2's fraud signals, as the one door into them — issue #108.
 *
 * <h2>IT ADVISES. IT DOES NOT DECIDE.</h2>
 *
 * <p>Nothing here refuses a pledge, holds money, suspends an account, or changes any state
 * outside this table. That is a deliberate refusal rather than an unfinished feature, and
 * it is worth being explicit about because the obvious next step is to wire the score into
 * the checkout.
 *
 * <p>Blocking a pledge on an automated score needs three things this platform does not
 * have: a false-positive rate somebody has measured, a way for a wrongly-blocked backer to
 * appeal, and a policy for what happens to a campaign whose funding was suppressed by our
 * arithmetic. The first is unmeasurable until #60 chooses a provider and a month of
 * chargebacks exists. Shipping the block first would mean the platform silently deciding
 * who may back a campaign, on numbers nobody has checked.
 *
 * <p><strong>Advisory is genuinely useful here rather than merely safe</strong>, and the
 * reason is §9.5: a confirmed pledge is not charged until the campaign reaches its goal at
 * its deadline. The gap between "flagged" and "money moves" is days or weeks, which is
 * plenty of time for the person the queue is for.
 *
 * <h2>Every assessment is written, including the quiet ones</h2>
 *
 * <p>A score below the review threshold still produces a row. The record of what was
 * noticed is the point — the question asked after a chargeback is "was this flagged at the
 * time", and "we did not write it down because it looked fine" is not an answer. Only the
 * queue filters; the table does not.
 *
 * <h2>A failed assessment never fails the thing it was assessing</h2>
 *
 * <p>{@link #assessPledge} is called from an outbox listener that shares its transaction
 * with every other consumer of the same event — {@code ApplicationEventOutboxDispatcher}
 * publishes one message to all of them — so a failure here would take the notification
 * fan-out down with it. A fraud signal is not entitled to veto an event for the modules
 * that share it, which is exactly the argument {@code NotificationRecipients} makes about
 * integrity errors.
 *
 * <p><strong>The listener catching is not enough, and finding that out is what this
 * paragraph is for.</strong> A {@code @Transactional} method that joins the caller's
 * transaction marks it rollback-only on the way out, so the caller catching the exception
 * changes nothing: the relay then fails at commit with
 * {@code UnexpectedRollbackException}, naming nothing that went wrong. The assessment
 * therefore runs {@link Propagation#REQUIRES_NEW} — the same choice
 * {@code AuditLog.recordIndependently} makes, for the same reason — so a failure rolls
 * back its own work and nobody else's, and the listener's catch means what it says.
 */
@Service
public class RiskAssessments {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessments.class);

    private final RiskFacts facts;
    private final RiskScorer scorer;
    private final AddressGeography geography;
    private final RiskAssessmentRepository assessments;
    private final RiskProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    public RiskAssessments(
            RiskFacts facts,
            RiskScorer scorer,
            AddressGeography geography,
            RiskAssessmentRepository assessments,
            RiskProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.facts = facts;
        this.scorer = scorer;
        this.geography = geography;
        this.assessments = assessments;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Scores one pledge and records what was noticed.
     *
     * @param confirmedAt when the pledge was confirmed, from the event rather than from the
     *     clock. An assessment that ran an hour late must judge the pledge against the hour
     *     it happened — the account was an hour younger, and the velocity window was a
     *     different window
     * @return what was written
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RiskAssessment assessPledge(UUID pledgeId, UUID projectId, UUID backerId, Instant confirmedAt) {
        Instant since = confirmedAt.minus(properties.velocity().window());

        Optional<String> address = facts.addressAt(backerId, confirmedAt);
        Set<String> known = facts.addressesUsedBy(backerId, confirmedAt);

        RiskInputs inputs = new RiskInputs(
                confirmedAt,
                address,
                facts.registeredAt(backerId),
                known,
                facts.pledgesByAccountSince(backerId, pledgeId, since),
                address.map(value -> facts.pledgesFromAddressSince(value, pledgeId, since)).orElse(0),
                address.flatMap(geography::countryOf),
                facts.destinationCountryOf(pledgeId));

        RiskScore score = scorer.score(inputs);

        RiskAssessment assessment = assessments.save(RiskAssessment.ofPledge(
                pledgeId,
                projectId,
                backerId,
                score.score(),
                score.decision(),
                json.writeValueAsString(score.findings()),
                score.signalsUnavailable(),
                confirmedAt));

        /*
         * Logged only when it reaches the queue, and with no backer and no address in the
         * line (§17.4). A line per pledge would be a line per pledge; a line per flagged
         * pledge is a signal an operator can watch the rate of.
         */
        if (score.decision() != RiskDecision.ALLOW) {
            log.info(
                    "Pledge {} scored {} and is queued for review ({} signals unavailable).",
                    pledgeId,
                    score.score(),
                    score.signalsUnavailable());
        }
        return assessment;
    }

    /** §4.11's AD-02 queue: what needs looking at, worst first. */
    @Transactional(readOnly = true)
    public List<RiskAssessment> queue(int limit) {
        return assessments.queue(PageRequest.ofSize(limit));
    }

    /** Everything ever noticed about one pledge, newest first. */
    @Transactional(readOnly = true)
    public List<RiskAssessment> historyOf(UUID pledgeId) {
        return assessments.findBySubjectTypeAndSubjectIdOrderByAssessedAtDesc(RiskAssessment.PLEDGE, pledgeId);
    }

    /**
     * Marks that somebody looked.
     *
     * <p>Empty when the assessment does not exist <em>or</em> has already been reviewed,
     * and the caller answers the same way for both: a second member of staff pressing the
     * button on a row their colleague has just taken should be told it is done, not that it
     * is missing.
     */
    @Transactional
    public Optional<RiskAssessment> markReviewed(UUID assessmentId, UUID staffId) {
        return assessments.findByIdAndReviewedAtIsNull(assessmentId).map(assessment -> {
            assessment.reviewedBy(staffId, clock.instant());
            return assessment;
        });
    }
}
