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

    @Override
    public String queueName() {
        return "Scheduled jobs";
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
