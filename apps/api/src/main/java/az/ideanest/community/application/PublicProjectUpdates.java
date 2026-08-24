package az.ideanest.community.application;

import az.ideanest.community.domain.ProjectUpdate;
import az.ideanest.community.infrastructure.ProjectUpdateRepository;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.PublicProjects;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a stranger may name this update — §4.11's AD-09, issue #297.
 *
 * <p><strong>{@code PublicComments}' shape, deliberately.</strong> That class exists so
 * that the moderation module can ask "is there a comment there worth complaining about"
 * without reading {@code comments}, and this is the same question about
 * {@code project_updates}. A cross-module read into {@code ProjectUpdateService} — whose
 * every other method starts with an authorisation check about the campaign's team — is how
 * a public question eventually inherits the wrong one.
 *
 * <h2>What #297 was blocked on, and what it turned out to be</h2>
 *
 * <p>The issue says "PROJECT_UPDATE has no report route, so half the queue has no intake",
 * and {@code ReportTargetType} says the reason is that "{@code project_updates} does not
 * exist". That was true when both were written and stopped being true with #83, which
 * built the table. So the block was one class and one controller method, exactly as #102
 * predicted it would be for comments: "V23's check constraint already named the value, and
 * publishing the route cost a controller method, a {@code ReportTargets} branch, and no
 * migration at all."
 *
 * <h2>A scheduled update is not reportable</h2>
 *
 * <p>§10.2 lets a creator publish an update with a future {@code publishedAt}, and nobody
 * outside the campaign's team can read one until then. Accepting a report about it would
 * be an oracle: a caller who guessed an identifier and got a 202 would learn that an
 * unpublished update exists, which is the same disclosure {@code ProjectAccess} spends its
 * comment refusing to make about a draft campaign.
 *
 * <p>The campaign is checked too, through {@link PublicProjects} — so an update under a
 * draft or suspended campaign cannot be used to find out that the campaign is there.
 */
@Service
public class PublicProjectUpdates {

    private final ProjectUpdateRepository updates;
    private final PublicProjects projects;
    private final Clock clock;

    public PublicProjectUpdates(ProjectUpdateRepository updates, PublicProjects projects, Clock clock) {
        this.updates = updates;
        this.projects = projects;
        this.clock = clock;
    }

    /**
     * Establishes that there is an update there worth complaining about.
     *
     * @throws ProjectUpdateNotFoundException when there is nothing there this caller is
     *     allowed to know exists — for an identifier that names nothing, for one that is
     *     scheduled, and for one under a campaign a stranger cannot see. Deliberately the
     *     same answer for all three
     */
    @Transactional(readOnly = true)
    public void requireReportable(UUID updateId) {
        ProjectUpdate update =
                updates.findById(updateId).orElseThrow(() -> new ProjectUpdateNotFoundException(updateId));

        if (!update.isPublishedAsOf(clock.instant())) {
            throw new ProjectUpdateNotFoundException(updateId);
        }
        try {
            projects.requireVisible(update.getProjectId());
        } catch (ProjectNotFoundException e) {
            throw new ProjectUpdateNotFoundException(updateId);
        }
    }
}
