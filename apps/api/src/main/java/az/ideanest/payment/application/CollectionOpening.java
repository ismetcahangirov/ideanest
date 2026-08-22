package az.ideanest.payment.application;

import az.ideanest.pledge.application.PledgeCollection;
import az.ideanest.project.application.CampaignCollections;
import az.ideanest.project.application.CollectingCampaign;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One campaign moved to {@code COLLECTING} and every one of its confirmed pledges
 * queued, in one transaction (#64).
 *
 * <p><strong>The transaction boundary is the whole reason this class exists</strong>
 * rather than being four lines inside {@link ChargeProcessorJob} — the same argument
 * {@code CampaignFinalizer} makes about closing a campaign, with a different pair of
 * writes. Two things have to commit together:
 *
 * <ol>
 *   <li>§6.1's {@code SUCCESSFUL → COLLECTING}, with its row in
 *       {@code project_state_transitions};
 *   <li>§6.2's {@code CONFIRMED → CHARGE_PENDING} for every pledge on the campaign, with
 *       §9.6's schedule frozen onto each one.
 * </ol>
 *
 * <p>Either without the other is its own kind of wrong. A campaign that says
 * {@code COLLECTING} with nothing queued is a campaign nobody will ever charge and
 * nothing will ever notice — there is no second pass that would find it, because
 * {@code findAwaitingCollection} looks for {@code SUCCESSFUL}. Queued pledges under a
 * campaign still saying {@code SUCCESSFUL} is worse: the next pass opens it again, and
 * queuing resets every backer's attempt count to zero, handing four fresh attempts to
 * cards that have already been refused four times.
 *
 * <p><strong>It is also the only place that can see both modules.</strong> §16.1 keeps
 * {@code Project} in one and {@code Pledge} in the other, so the requirement that they
 * commit together cannot be stated in either of them. It is stated here, in the module
 * that owns collection.
 *
 * <h2>This is not gated on a provider</h2>
 *
 * <p>Unlike {@code CollectionRun}, opening a collection happens whether or not a
 * provider is configured, and that is deliberate. Moving a campaign to
 * {@code COLLECTING} and queuing its pledges is a statement about what the platform
 * owes — the campaign closed above goal and these backers are committed — and it is
 * true regardless of whether anybody can be charged yet. It is also what makes the
 * state visible: a fleet with no provider accumulates campaigns in
 * {@code COLLECTING} with pledges in {@code CHARGE_PENDING}, which is a queue somebody
 * can look at, rather than campaigns sitting in {@code SUCCESSFUL} looking finished.
 *
 * <p>The honest consequence, and it is a real one: those pledges have §9.6's window
 * running against them, so a platform that stayed provider-less for seven days would
 * drop them. That is the correct behaviour for a promise the platform cannot keep, and
 * it is visible from the first pass rather than on the seventh day.
 */
@Service
public class CollectionOpening {

    private static final Logger log = LoggerFactory.getLogger(CollectionOpening.class);

    private final CampaignCollections campaigns;
    private final PledgeCollection pledges;
    private final RetrySchedule schedule;

    public CollectionOpening(CampaignCollections campaigns, PledgeCollection pledges, RetrySchedule schedule) {
        this.campaigns = campaigns;
        this.pledges = pledges;
        this.schedule = schedule;
    }

    /**
     * Opens one campaign's collection, or finds that somebody else already has.
     *
     * <p>{@link Propagation#REQUIRES_NEW} for {@code CampaignFinalizer#finalise}'s
     * reason: the sweep that calls this is not transactional, and a caller that drove it
     * from inside one would silently turn "one campaign per transaction" into "one pass
     * per transaction" — which would make one campaign's failure roll back every campaign
     * the pass had already opened.
     *
     * @param projectId a campaign {@code CampaignCollections#awaitingCollection} selected
     * @param now the pass's instant. §9.6's schedule is measured from it, so every pledge
     *     on one campaign gets the same window and a run that starts ten minutes late
     *     gives its backers the same seven days rather than seven days minus ten minutes
     * @return how many pledges were queued, or empty when this call is not the one that
     *     opened the campaign
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> open(UUID projectId, Instant now) {
        Optional<CollectingCampaign> campaign = campaigns.beginCollection(projectId, now);
        if (campaign.isEmpty()) {
            return Optional.empty();
        }

        int queued = pledges.queueForCollection(projectId, schedule.firstAttemptAt(now), schedule.windowEndsAt(now));

        // The count and the campaign, and no amount. A campaign with nothing to queue is
        // worth an explicit line rather than a silence: it means every confirmed pledge
        // was cancelled between the deadline and this pass, which is unusual enough that
        // somebody should be able to find it afterwards.
        if (queued == 0) {
            log.warn("Campaign {} opened its collection with no confirmed pledges to queue.", projectId);
        } else {
            log.info("Campaign {} opened its collection; {} pledges queued.", projectId, queued);
        }
        return Optional.of(queued);
    }
}
