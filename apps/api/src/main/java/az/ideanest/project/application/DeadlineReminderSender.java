package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.project.infrastructure.DeadlineNoticeRepository;
import az.ideanest.project.infrastructure.ProjectRepository;
import az.ideanest.shared.outbox.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One campaign, one threshold, claimed and announced in one transaction.
 *
 * <p>The deadline half of §4.10's reminders, and the counterpart of
 * {@link LaunchReminderSender} — but built on a different mechanism, because the two are
 * different problems. A launch notice is owed to a finite list of people who each asked for it,
 * so the claim is per person and lives on their row. A deadline notice is owed to an audience
 * the platform computes and which nobody enrolled in, so there is no row to stamp per person:
 * what is claimed is the <em>announcement</em>, once per campaign per threshold, and
 * {@code deadline_notices} is that claim.
 *
 * <p><strong>The transaction boundary is the whole reason this class exists</strong> rather
 * than being four lines inside {@link DeadlineReminderJob}, and it is {@code CampaignFinalizer}'s
 * argument exactly. Two things commit together:
 *
 * <ol>
 *   <li>the {@code deadline_notices} row, which is what stops the next tick sending the same
 *       message again for the rest of the campaign's last two days;
 *   <li>the {@code project.ending_soon} outbox event, which is how anybody hears about it.
 * </ol>
 *
 * <p>Either without the other is a distinct kind of wrong. A claim with no event is a campaign
 * whose backers are never told, permanently, because the claim is what makes the sweep skip it.
 * An event with no claim is a message every minute for two days.
 *
 * <p><strong>And one campaign per transaction, not one pass per transaction</strong>, for
 * {@code CampaignFinalizer}'s reason: a batch that shared a transaction would let one campaign's
 * failure roll back every campaign the pass had already announced, and every campaign behind
 * this one is also closing.
 *
 * <h2>What this does not decide</h2>
 *
 * <p><strong>Who is told.</strong> This module records that a campaign is closing; §4.10's
 * audiences — the campaign's backers, the people who saved it — are resolved by the
 * notification module through {@code shared.audience.ProjectAudiences}. That separation is
 * #245's whole point, and it is why this class needs to know nothing about {@code saves}.
 *
 * <p><strong>Whether anybody wants the message.</strong> That is the recipient's preference and
 * {@code NotificationFanOut}'s job.
 */
@Service
public class DeadlineReminderSender {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderSender.class);

    private final ProjectRepository projects;
    private final DeadlineNoticeRepository notices;
    private final Outbox outbox;
    private final Clock clock;

    public DeadlineReminderSender(
            ProjectRepository projects, DeadlineNoticeRepository notices, Outbox outbox, Clock clock) {
        this.projects = projects;
        this.notices = notices;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Campaigns within {@code thresholdHours} of closing that have not been announced at that
     * threshold.
     *
     * <p>Read outside the announcing transaction on purpose: it is a bounded list of candidates
     * and every one of them is re-checked by the claim, which is the only check that counts.
     */
    @Transactional(readOnly = true)
    public List<UUID> nearing(int thresholdHours, int batchSize) {
        return notices.nearing(thresholdHours, clock.instant(), batchSize);
    }

    /**
     * Claims a threshold for one campaign and announces it, or does nothing because somebody
     * else got there first.
     *
     * <p><strong>The campaign is re-read and re-checked inside this transaction.</strong> The
     * candidate list was assembled a moment ago and a campaign can be cancelled, suspended or
     * finalised in between; announcing "48 hours remaining" about a campaign that has just been
     * cancelled would be a message contradicting the one its backers received seconds earlier.
     * The claim alone would not catch that — it only knows about thresholds.
     *
     * @return true when this call announced the campaign
     */
    @Transactional
    public boolean announce(UUID projectId, int thresholdHours) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !project.isLive() || project.getDeadline() == null) {
            // Gone, or no longer live. Not an error and not worth a line above debug: the
            // candidate list is a snapshot and this is what a snapshot going stale looks like.
            log.debug("Campaign {} is no longer a candidate for a {}h notice.", projectId, thresholdHours);
            return false;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!project.getDeadline().isAfter(now)) {
            // The deadline passed between the candidate read and here. `campaign-finalizer` owns
            // what happens next; a "closing soon" notice about a campaign that has closed is
            // the one message this sweep must never send.
            return false;
        }
        if (!notices.claim(projectId, thresholdHours, now)) {
            // Another replica claimed it. The ordinary outcome of two schedulers agreeing, and
            // the reason the claim is a conditional insert rather than a check.
            return false;
        }

        UUID eventId = outbox.record(
                CampaignEndingSoonEvent.AGGREGATE_TYPE,
                projectId,
                CampaignEndingSoonEvent.EVENT_TYPE,
                new CampaignEndingSoonEvent(
                        projectId, project.getCreatorId(), thresholdHours, project.getDeadline(), now));

        log.debug("Campaign {} crossed the {}h threshold; recorded outbox event {}.", projectId, thresholdHours, eventId);
        return true;
    }
}
