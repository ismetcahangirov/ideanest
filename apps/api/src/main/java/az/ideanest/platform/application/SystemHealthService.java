package az.ideanest.platform.application;

import az.ideanest.platform.PlatformProperties;
import az.ideanest.platform.application.SystemHealth.HealthStatus;
import az.ideanest.platform.application.SystemHealth.JobHealth;
import az.ideanest.platform.application.SystemHealth.ProviderHealth;
import az.ideanest.platform.application.SystemHealth.QueueDepth;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.shared.jobs.JobRecord;
import az.ideanest.shared.jobs.JobRecordRepository;
import az.ideanest.shared.jobs.JobState;
import az.ideanest.shared.observability.ProviderStatusSource;
import az.ideanest.shared.observability.QueueDepthSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AD-16's screen, assembled — §18 and issue #316.
 *
 * <h2>Every number here is a count this service can already take</h2>
 *
 * <p>#316 was labelled blocked on #138. It was not, quite, and {@link SystemHealth}'s
 * comment has the distinction: §18 is continuous monitoring that wakes somebody, and this
 * is a page somebody opens. The queues are counted through {@link QueueDepthSource}, so
 * the modules that own them answer; the jobs come from {@code scheduled_jobs}, which is
 * {@code shared}; the providers come from the breaker that is already deciding whether to
 * call them.
 *
 * <p><strong>It is not monitoring and the screen says so.</strong> Nothing here alerts.
 * Presenting a dashboard as though it were monitoring is worse than an honest gap,
 * because the gap then looks filled.
 *
 * <h2>{@code VIEW_HEALTH}, and why it is not staff-wide</h2>
 *
 * <p>The rows carry no personal data, so the argument for narrowing it is not
 * confidentiality — it is that a queue depth is meaningless to somebody who does not
 * operate the platform, and a console that offers every screen to everybody is one where
 * the useful ones are harder to find. Every role that plausibly needs it has it.
 */
@Service
public class SystemHealthService {

    private final List<QueueDepthSource> queues;
    private final JobRecordRepository jobs;
    private final List<ProviderStatusSource> providers;
    private final PlatformStaff staff;
    private final PlatformProperties properties;
    private final java.time.Clock clock;

    public SystemHealthService(
            List<QueueDepthSource> queues,
            JobRecordRepository jobs,
            List<ProviderStatusSource> providers,
            PlatformStaff staff,
            PlatformProperties properties,
            java.time.Clock clock) {
        this.queues = queues;
        this.jobs = jobs;
        this.providers = providers;
        this.staff = staff;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The whole screen, as of now.
     *
     * <p><strong>Not audited</strong>, unlike every other console read. The rule the
     * audited reads follow is that they hand over something about a person —
     * {@code ACCOUNTS_SEARCHED} exists because a directory read discloses an email
     * address. A queue depth discloses nothing about anybody, and recording every refresh
     * of a dashboard somebody leaves open would bury the rows that matter under the ones
     * that do not. {@code AuditAction}'s own comment on {@code AUDIT_TRAIL_READ} draws the
     * line in the same place and for the same reason.
     */
    @Transactional(readOnly = true)
    public SystemHealth snapshot(UUID staffId) {
        staff.requireCapability(staffId, StaffCapability.VIEW_HEALTH);

        Instant now = clock.instant();
        PlatformProperties.Health thresholds = properties.health();

        List<QueueDepth> depths = queues.stream()
                .map(source -> QueueDepth.of(source.queueName(), source.waiting(), source.dead(), thresholds))
                .sorted(Comparator.comparing(QueueDepth::name))
                .toList();

        List<JobHealth> jobHealth = jobs.allJobs().stream()
                .map(job -> gradeJob(job, now, thresholds.staleJobAfter()))
                .toList();

        List<ProviderHealth> providerHealth = gradeProviders();

        HealthStatus overall = HealthStatus.HEALTHY;
        for (QueueDepth depth : depths) {
            overall = overall.or(depth.status());
        }
        for (JobHealth job : jobHealth) {
            overall = overall.or(job.status());
        }
        for (ProviderHealth provider : providerHealth) {
            overall = overall.or(provider.status());
        }

        return new SystemHealth(now, depths, jobHealth, providerHealth, overall);
    }

    /**
     * How one job is doing.
     *
     * <p>{@code DEAD} is critical because it will not restart itself. Being overdue is
     * degraded rather than critical until it has been overdue for longer than the
     * configured window — a scheduler under load is behind by seconds, and a scheduler
     * that is not running is behind by hours. {@code PlatformProperties.Health} carries
     * the argument for the number.
     */
    private JobHealth gradeJob(JobRecord job, Instant now, Duration staleAfter) {
        Instant nextAttemptAt = job.getNextAttemptAt();
        long overdueBy = nextAttemptAt == null || nextAttemptAt.isAfter(now)
                ? 0L
                : Duration.between(nextAttemptAt, now).toSeconds();

        HealthStatus status;
        if (job.getState() == JobState.DEAD) {
            status = HealthStatus.CRITICAL;
        } else if (overdueBy > staleAfter.toSeconds()) {
            status = HealthStatus.CRITICAL;
        } else if (overdueBy > 0) {
            status = HealthStatus.DEGRADED;
        } else {
            status = HealthStatus.HEALTHY;
        }

        return new JobHealth(
                job.getName(),
                job.getState().name(),
                job.getLastRunAt(),
                nextAttemptAt,
                overdueBy,
                job.getAttempts(),
                job.getLastError(),
                status);
    }

    /**
     * What every module that calls a third party says about it.
     *
     * <p><strong>Asked through {@link ProviderStatusSource} rather than read from the
     * payment module.</strong> The first version of this method named
     * {@code ProviderCircuitBreaker} and {@code ProviderName} directly, and
     * {@code ModuleBoundaryTests} refused it — correctly, because a health screen that knew
     * §9.3's provider vocabulary would change every time the list of integrations did, for a
     * page that only ever renders what it is handed.
     *
     * <p><strong>An unconfigured provider is healthy.</strong> §9.3 asks for at least two
     * integrations and a deployment may run one; painting the others red would put permanent
     * failures on a screen whose only job is to show the ones that are not permanent. It is
     * reported as not configured, which is a different column.
     */
    private List<ProviderHealth> gradeProviders() {
        List<ProviderHealth> health = new ArrayList<>();

        for (ProviderStatusSource source : providers) {
            for (ProviderStatusSource.ProviderStatus status : source.providerStatuses()) {
                health.add(new ProviderHealth(
                        status.kind(),
                        status.name(),
                        status.configured(),
                        status.available(),
                        status.detail(),
                        !status.configured() || status.available()
                                ? HealthStatus.HEALTHY
                                : HealthStatus.CRITICAL));
            }
        }

        health.sort(Comparator.comparing(ProviderHealth::kind).thenComparing(ProviderHealth::provider));
        return health;
    }
}
