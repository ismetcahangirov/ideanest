package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.infrastructure.ProjectRepository;
import az.ideanest.shared.jobs.ScheduledJob;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * §8.4's {@code campaign-launcher}: campaigns open at the time their creator chose.
 *
 * <p><strong>The half of §6.1 nothing performed.</strong> The state machine has had
 * {@code APPROVED → SCHEDULED → LIVE} since it was written and the campaign editor tells a
 * creator their cleared campaign "goes live when you launch it, or at the launch time you
 * set". Only the first was true: {@code ProjectTransitionService.launch} was the sole
 * producer of {@code LIVE} and it needs somebody signed in to press something. A creator who
 * set a launch time for nine on Monday morning and was not at a keyboard at nine on Monday
 * morning had a campaign that did not open, told nobody, and appeared in no listing.
 *
 * <p>A {@link ScheduledJob} rather than {@code @Scheduled}, like every job here since #134:
 * the tick claims a lease, so one replica does the work and the attempt is recorded.
 *
 * <p><strong>One campaign's failure is one campaign's failure.</strong> Each is launched in
 * its own transaction and a throw is caught here, because a batch that abandoned the rest on
 * the first bad row would leave campaigns closed for the rest of the day over one of them.
 * The pass comes back a minute later and tries again, which for a transient failure is the
 * whole recovery.
 */
@Component
public class CampaignLauncherJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignLauncherJob.class);

    private final ProjectRepository projects;
    private final ProjectTransitionService transitions;
    private final ProjectProperties properties;
    private final Clock clock;

    public CampaignLauncherJob(
            ProjectRepository projects,
            ProjectTransitionService transitions,
            ProjectProperties properties,
            Clock clock) {

        this.projects = projects;
        this.transitions = transitions;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "campaign-launcher";
    }

    @Override
    public String schedule() {
        return properties.launches().schedule();
    }

    @Override
    public void run() {
        launchDueCampaigns(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Every campaign whose launch time has arrived, up to one batch.
     *
     * @param now the instant the pass judges against, so that one pass reads one clock and a
     *     test can drive it without waiting for a minute to pass
     * @return how many campaigns this pass opened
     */
    public int launchDueCampaigns(Instant now) {
        List<UUID> due =
                projects.findDueForLaunch(now, PageRequest.ofSize(properties.launches().batchSize()));

        int launched = 0;
        for (UUID projectId : due) {
            try {
                if (transitions.launchScheduled(projectId, now).isPresent()) {
                    launched++;
                }
            } catch (RuntimeException e) {
                log.error("Could not open campaign {}; it stays closed until the next pass.", projectId, e);
            }
        }

        if (launched > 0) {
            log.info("Opened {} of {} campaigns whose launch time had arrived.", launched, due.size());
        }
        return launched;
    }
}
