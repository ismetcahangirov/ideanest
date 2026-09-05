package az.ideanest.obligation.application;

import az.ideanest.obligation.ObligationProperties;
import az.ideanest.obligation.domain.UpdateObligation;
import az.ideanest.shared.jobs.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * §8.4's {@code update-obligation-sweep}: the trigger for §5.5's monthly clock — issue #437.
 *
 * <h2>Daily, because the obligation is monthly</h2>
 *
 * <p>A sweep every minute would be fourteen hundred passes a day finding the same nothing. Nobody
 * can tell a reminder sent at 03:15 from one sent at 09:00 when the thing being reminded about is
 * due in seven days — and the state a campaign page reads is a comparison rather than a column, so
 * a pass that is late still shows the right thing on the page and sends the reminder a few hours
 * later. That is the correct failure for a sweep to have.
 *
 * <p>Only the orchestration is here; every write is {@link ObligationSweeper}'s, one transaction
 * per obligation. {@code DeadlineReminderJob} and {@code DeadlineReminderSender} are split the
 * same way and for the same proxy reason.
 */
@Component
public class ObligationSweepJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ObligationSweepJob.class);

    private final ObligationSweeper sweeper;
    private final ObligationProperties properties;
    private final Clock clock;

    public ObligationSweepJob(ObligationSweeper sweeper, ObligationProperties properties, Clock clock) {
        this.sweeper = sweeper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "update-obligation-sweep";
    }

    /**
     * A property so the test profile can set it to {@code -} and drive {@link #sweep()} directly —
     * a timer firing in the background of a suite acts on the very rows a test is about to assert
     * on.
     */
    @Override
    public String schedule() {
        return properties.schedule();
    }

    @Override
    public void run() {
        sweep();
    }

    /**
     * One pass.
     *
     * <p>The horizon is {@code now + reminderLead}, so one query finds both the obligations due a
     * warning and the ones that have already lapsed. Two queries would scan the same partial index
     * twice per pass to answer a question the row already carries.
     *
     * @return how many obligations this pass acted on
     */
    public int sweep() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<UpdateObligation> candidates =
                sweeper.candidates(now.plus(properties.reminderLead()), Limit.of(properties.perPass()));

        int acted = 0;
        for (UpdateObligation candidate : candidates) {
            if (sweeper.act(candidate.projectId(), now)) {
                acted++;
            }
        }
        if (acted > 0) {
            log.info("update-obligation-sweep acted on {} of {} candidates", acted, candidates.size());
        }
        return acted;
    }
}
