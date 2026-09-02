package az.ideanest.shared.jobs;

import az.ideanest.shared.observability.QueueDepthSource;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The scheduler's contribution to AD-16's screen — #316.
 *
 * <p><strong>"Waiting" is jobs that are due and have not run</strong>, not every
 * registered job. A job whose next attempt is tomorrow is scheduled rather than queued,
 * and counting it would make the screen show a permanent backlog equal to the number of
 * jobs in the codebase — a number that never goes down, which is how a dashboard teaches
 * people to ignore it.
 */
@Component
public class JobQueueDepth implements QueueDepthSource {

    private final JobRecordRepository jobs;
    private final Clock clock;

    public JobQueueDepth(JobRecordRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * The queue's name as an identifier, not as a sentence — issue #405.
     *
     * <p>This used to answer "Scheduled jobs", which the console rendered verbatim under an
     * Azerbaijani heading and directly above a section headed "Planlaşdırılmış işlər" — the
     * Azerbaijani for the same three words. One concept, two languages, one screen.
     *
     * <p>A queue name is a machine value: it is a metric tag, and it is the key the console
     * looks a translated label up under. That is the rule §21.1 and {@code HealthDashboard}
     * already follow for a job's state and for a capability — the wire word stays the wire
     * word and the sentence beside it is the reader's. A name a screen can render without a
     * catalogue is one that can only ever be in one language.
     */
    @Override
    public String queueName() {
        return "scheduled-jobs";
    }

    @Override
    public long waiting() {
        return jobs.countDue(clock.instant());
    }

    @Override
    public long dead() {
        return jobs.countByState(JobState.DEAD);
    }
}
