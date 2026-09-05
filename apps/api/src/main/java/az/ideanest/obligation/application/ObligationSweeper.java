package az.ideanest.obligation.application;

import az.ideanest.obligation.ObligationProperties;
import az.ideanest.obligation.domain.UpdateObligation;
import az.ideanest.obligation.infrastructure.UpdateObligationRepository;
import az.ideanest.shared.outbox.Outbox;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warns the creators whose month is nearly up, and puts the ones whose month is up in front of a
 * moderator — issue #437.
 *
 * <p><strong>Separate from {@link ObligationSweepJob}, which is only the trigger.</strong>
 * {@code DeadlineReminderSender} and {@code DeadlineReminderJob} are split the same way, for a
 * reason that is not taste: {@code @Transactional} is applied by a proxy, so a job that called its
 * own transactional method would run every claim outside a transaction and the row and its event
 * would stop committing together.
 *
 * <h2>One obligation per transaction, not one pass per transaction</h2>
 *
 * <p>{@code CampaignFinalizer}'s argument and {@code DeadlineReminderSender}'s: a batch that
 * shared a transaction would let one campaign's failure roll back every reminder the pass had
 * already sent, and every campaign behind this one is also overdue.
 *
 * <h2>What the two claims guarantee</h2>
 *
 * <p>Both are conditional writes on the row, keyed to the due date they are made for. So the
 * reminder is sent once per cycle and <strong>the escalation happens once rather than every
 * morning</strong> — which is #437's test by name. The due date does not move until an update
 * moves it, and an update is exactly what clears the lapse.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not refund, suspend, hide or de-rank anything. §9.7 says a creator who cannot
 * deliver "offers a refund; the platform mediates", and suspension is a moderator decision under
 * §4.11's AD-02 — so the escalation is the output and a human being is the next step.
 */
@Service
public class ObligationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ObligationSweeper.class);

    private final UpdateObligationRepository obligations;
    private final Outbox outbox;
    private final ObligationProperties properties;

    public ObligationSweeper(
            UpdateObligationRepository obligations, Outbox outbox, ObligationProperties properties) {
        this.obligations = obligations;
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * Running obligations whose next event has arrived — a warning's or a lapse's.
     *
     * <p>Read outside the acting transactions on purpose, following
     * {@code DeadlineReminderSender}: it is a bounded list of candidates and every one of them is
     * re-checked by its own claim, which is the only check that counts.
     */
    @Transactional(readOnly = true)
    public List<UpdateObligation> candidates(Instant horizon, Limit limit) {
        return obligations.owing(horizon, limit);
    }

    /**
     * Warns or escalates one obligation, or does nothing because somebody else got there first.
     *
     * <p><strong>The row is re-read inside this transaction.</strong> The candidate list was
     * assembled a moment ago and a creator can publish an update in between — warning somebody
     * their update is due seconds after they published one is the message this sweep must never
     * send. The claim alone would not catch it: a claim only knows about due dates, and
     * publishing an update is what moves the due date.
     *
     * @return true when this call warned or escalated
     */
    @Transactional
    public boolean act(UUID projectId, Instant now) {
        UpdateObligation obligation = obligations.findById(projectId).orElse(null);
        if (obligation == null || obligation.closedAt() != null) {
            // Gone, or fulfilment completed between the candidate read and here. Not an error:
            // this is what a snapshot going stale looks like.
            return false;
        }

        if (!now.isBefore(obligation.dueAt())) {
            if (!obligation.claimLapse(now)) {
                // Already escalated for this cycle. The ordinary outcome of a daily sweep over a
                // campaign that stays silent, and the reason the claim is a due date rather than
                // a boolean.
                return false;
            }
            obligations.save(obligation);
            // No event and no notification. The escalation is the row being visible in the
            // moderator's queue, and the consequence is the state on the campaign's own page.
            log.info("Campaign {} lapsed its §5.5 update obligation, due {}", projectId, obligation.dueAt());
            return true;
        }

        if (!now.isBefore(obligation.dueAt().minus(properties.reminderLead()))) {
            if (!obligation.claimReminder()) {
                return false;
            }
            obligations.save(obligation);
            // The claim and the event, one transaction: a claim with no event is a creator who is
            // never warned, permanently, and an event with no claim is a message every morning.
            outbox.record(
                    UpdateDueSoonEvent.AGGREGATE_TYPE,
                    projectId,
                    UpdateDueSoonEvent.EVENT_TYPE,
                    new UpdateDueSoonEvent(
                            projectId, obligation.creatorId(), obligation.dueAt(), obligation.lastUpdateAt()));
            return true;
        }

        return false;
    }
}
