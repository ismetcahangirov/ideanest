package az.ideanest.project.application;

import az.ideanest.project.domain.Capability;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.project.domain.Project;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.2's {@code GET /v1/projects/{id}/dashboard} — #93.
 *
 * <h2>Who may read it</h2>
 *
 * <p>{@link ProjectCapability#VIEW_FINANCES}, which is the capability that exists for
 * exactly this: it is the one grant about reading rather than writing, and what it
 * exposes is what a campaign has raised. {@code ProjectAnalyticsService} and
 * {@code ReferralReportService} ask for the same one, so a collaborator granted the
 * finance view sees the three surfaces together and a collaborator granted only
 * {@code EDIT_REWARDS} sees none of them.
 *
 * <p>Asked through {@link ProjectAccess} rather than by comparing
 * {@code project.creatorId} to the caller. The comparison is the version that quietly
 * excludes collaborators, and #236 is the issue about four modules that each settled for
 * the coarsest check they could name.
 *
 * <h2>What it is not</h2>
 *
 * <p><strong>It is not the analytics endpoint and does not overlap it.</strong>
 * {@code GET /analytics} answers CD-02 — a series, one row per day, pre-aggregated by
 * #95's job and therefore as fresh as the last rollup. This answers CD-01: four numbers
 * read from {@code projects} at the moment of the request. They are deliberately separate
 * because their freshness is different, and a screen that mixed them would show a total
 * from this second beside a chart that stopped at midnight without saying so.
 *
 * <p><strong>It does not touch the campaign.</strong> Read-only, and the transaction says
 * so.
 */
@Service
public class CampaignDashboardService {

    private final ProjectAccess access;
    private final Clock clock;

    public CampaignDashboardService(ProjectAccess access, Clock clock) {
        this.access = access;
        this.clock = clock;
    }

    /**
     * The campaign's live totals and its clock.
     *
     * @param accountId the authenticated caller, from the access token's subject and
     *     never from the request
     * @throws ProjectNotFoundException when there is no such campaign — <strong>and when
     *     the caller is not party to it at all</strong>. {@code ProjectAccess} answers a
     *     stranger that way on purpose: a 403 would confirm that the identifier names a
     *     real campaign
     * @throws CapabilityNotGrantedException when the caller is a collaborator whose grant
     *     does not include {@link ProjectCapability#VIEW_FINANCES}. A 403, because there
     *     is nothing to be evasive about — they already know the campaign exists
     */
    @Transactional(readOnly = true)
    public CampaignDashboard dashboard(UUID projectId, UUID accountId) {
        // The overload that hands back the campaign, rather than the void
        // requireCapability plus a findById of our own. Two loads would be two rows, and
        // the second could differ from the one authorisation was decided on -- a
        // difference of one committed transaction, which is exactly the window a
        // creator's own dashboard refresh sits in. The name says "editable" and this is a
        // read; that is ProjectAccess's vocabulary rather than this method's, and the
        // capability being asked for is what decides what is allowed.
        Project project = access.requireEditable(projectId, accountId, Capability.of(ProjectCapability.VIEW_FINANCES));

        return CampaignDashboard.of(project, now());
    }

    /**
     * The instant this answer is computed at, and the one the client measures its own
     * clock against.
     *
     * <p>Truncated to microseconds for the reason every clock read in this codebase is:
     * PostgreSQL stores microseconds and Java offers nanoseconds, so an untruncated
     * instant is one that does not survive a round trip. It matters here even though
     * nothing is written — a test comparing this against a stored timestamp would fail on
     * digits nothing can store.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
