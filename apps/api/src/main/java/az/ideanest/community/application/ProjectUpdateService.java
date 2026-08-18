package az.ideanest.community.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.community.domain.ProjectUpdate;
import az.ideanest.community.domain.UpdateVisibility;
import az.ideanest.community.infrastructure.ProjectUpdateRepository;
import az.ideanest.project.application.CapabilityNotGrantedException;
import az.ideanest.project.application.ProjectAccess;
import az.ideanest.project.application.ProjectNotFoundException;
import az.ideanest.project.application.PublicProjects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing an update, and reading the ones a caller is allowed to see. §4.4's
 * Updates tab and §4.7's CD-12.
 *
 * <h2>What decides the number</h2>
 *
 * <p>Allocated as {@code max + 1} per campaign, behind a lock on the newest row —
 * {@link ProjectUpdateRepository#lockNewest}. A campaign's very first update has no row
 * to lock, so that one race is decided by {@code project_updates_number_key} and the
 * loser is told to try again ({@link UpdateNumberContendedException}) rather than being
 * handed a 500 about a constraint it has never heard of.
 *
 * <h2>Why the schedule may not move backwards</h2>
 *
 * <p>The page lists updates by number and the number is allocated on insert, so
 * {@code published_at} going backwards between consecutive updates would put update 6
 * on the page a week after update 7. §4.4 calls them "numbered updates", and a numbered
 * sequence that arrives out of order is not one. So a schedule earlier than the newest
 * update's is refused, with the boundary named, and a creator who genuinely wants to
 * say something now says it now — it becomes the next number, which is what a reader
 * expects of the newest post.
 *
 * <h2>Authorisation, and the one thing this release cannot ask</h2>
 *
 * <p><strong>Writing</strong> goes through {@link ProjectAccess#requireEditable(UUID, UUID)}:
 * the creator, or a collaborator holding any editing capability. §7.2 defines a
 * {@code PUBLISH_UPDATES} capability and this is deliberately <em>not</em> asking for
 * it — the fine-grained form of that method takes a {@code Capability}, which lives in
 * the project module's {@code domain} package and is unreachable from here by
 * {@code ModuleBoundaryTests}. The reward module makes the same compromise for the same
 * reason ({@code RewardAccess}), which is why this matches it rather than inventing a
 * second convention. Closing it is a method on {@code ProjectAccess} that names the
 * capability and answers with something this module may hold; that is a change to
 * another module and is not in this issue.
 *
 * <p><strong>Reading</strong> has two audiences and they are decided in one place,
 * {@link #audienceOf}. The campaign's own team sees every update including the ones
 * scheduled for later; everybody else sees the published, public ones — and only if
 * {@link PublicProjects} says the campaign itself may be read at all.
 *
 * <p><strong>{@link UpdateVisibility#BACKERS_ONLY} is not yet shown to backers</strong>,
 * and that is the honest gap in this issue rather than an oversight. "Has this account
 * an active pledge on this campaign" is a question about {@code pledges}, and the pledge
 * module's application layer publishes no answer to it: {@code PublicBackers} counts
 * backers and deliberately exposes none of them (#209). Rather than reach into another
 * module's tables, this release fails closed — a backers-only update is withheld from
 * everybody outside the team, which is a promise kept too tightly rather than broken.
 * The alternative failure, showing a backers-only update to the public, is the one that
 * cannot be taken back.
 */
@Service
public class ProjectUpdateService {

    /**
     * How far ahead an update may be scheduled.
     *
     * <p>A bound rather than a policy: a campaign runs for at most sixty days (§5.3) and
     * fulfilment follows it, so a year covers every legitimate case. What it stops is a
     * client with a millisecond/second confusion scheduling an update for the year
     * 55000, where it would sit forever in a table nothing sweeps.
     */
    private static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(365);

    /**
     * How far in the past a {@code publishAt} may be before it is refused.
     *
     * <p>Not zero. A client that computes "now" from its own clock and posts it is doing
     * the correct thing, and refusing it because two machines disagree by four hundred
     * milliseconds would be a refusal about NTP. Anything older than this is a date
     * somebody typed, and back-dating an announcement to people who were not told is not
     * something to accept silently.
     */
    private static final Duration SCHEDULE_GRACE = Duration.ofMinutes(1);

    /** What a page holds when the client does not say. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** The most a client may ask for in one page. */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The first page's cursor: everything numbered below it, which is everything.
     *
     * <p>A value rather than a null bind. The predicate would otherwise have to be
     * {@code (:below IS NULL OR u.number < :below)}, and an untyped null is exactly the
     * parameter PostgreSQL declines to infer a type for.
     */
    private static final int NO_CURSOR = Integer.MAX_VALUE;

    /**
     * Stands in for "no upper bound on publication" without being
     * {@link Instant#MAX}, which PostgreSQL's {@code timestamptz} cannot hold — its
     * range ends in the year 294276 and {@code Instant.MAX} is four orders of magnitude
     * past that. Binding it would fail at the driver, on the read path, for the team.
     */
    private static final Instant THE_FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    private static final Set<UpdateVisibility> EVERYTHING = EnumSet.allOf(UpdateVisibility.class);

    private static final Set<UpdateVisibility> PUBLIC_ONLY = EnumSet.of(UpdateVisibility.PUBLIC);

    private final ProjectUpdateRepository updates;
    private final ProjectAccess access;
    private final PublicProjects publicProjects;
    private final AuditLog audit;
    private final Clock clock;

    public ProjectUpdateService(
            ProjectUpdateRepository updates,
            ProjectAccess access,
            PublicProjects publicProjects,
            AuditLog audit,
            Clock clock) {

        this.updates = updates;
        this.access = access;
        this.publicProjects = publicProjects;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Publishes an update, now or at the moment it was scheduled for.
     *
     * <p>Audited. Publishing is a statement made in the campaign's name to everybody
     * following it, it is the obligation §5.5 imposes on a funded creator, and it is not
     * reversible by any endpoint — so "who said this, on which campaign, from where" has
     * to be answerable by somebody who was not in the room. The audit row is written by
     * this transaction ({@link AuditLog#record}), so the update and the record of it
     * commit together or neither does.
     *
     * <p>The audit detail carries the number, the audience and whether it was scheduled,
     * and deliberately not the title or the body. {@code AuditLog} says to record what
     * happened rather than to copy what it happened to: the text is in
     * {@code project_updates} under that table's retention, and the audit table has none
     * yet.
     *
     * @param accountId the authenticated caller, never a value from a request body
     * @throws ProjectNotFoundException when there is no such campaign, and when this
     *     caller has no relationship to it — deliberately the same answer
     * @throws CapabilityNotGrantedException when the caller works on the campaign and
     *     holds no editing capability
     * @throws UpdateScheduleInvalidException when {@code publishAt} is in the past, too
     *     far away, or earlier than the newest update
     */
    @Transactional
    public ProjectUpdate publish(UUID projectId, UUID accountId, NewUpdate command) {
        // Loading and checking are one call, so there is no way to reach the table
        // without having been authorised for the campaign it belongs to. The campaign
        // itself is discarded: it is another module's entity and this one may not name
        // it, which ModuleBoundaryTests enforces.
        access.requireEditable(projectId, accountId);

        Optional<ProjectUpdate> newest = updates.lockNewest(projectId);
        Instant publishAt = scheduleOf(command.publishAt(), newest);
        int number = newest.map(ProjectUpdate::getNumber).orElse(0) + 1;

        ProjectUpdate update = ProjectUpdate.publish(
                projectId,
                number,
                command.title(),
                command.body(),
                command.visibility(),
                accountId,
                publishAt);

        ProjectUpdate stored = save(projectId, update);

        audit.record(
                AuditAction.PROJECT_UPDATE_PUBLISHED,
                projectId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "number=" + number
                        + " visibility=" + command.visibility()
                        + " scheduled=" + publishAt.isAfter(clock.instant()));

        return stored;
    }

    /**
     * One page of the campaign's updates, as this caller is allowed to see them.
     *
     * <p>The team is asked about <em>before</em> {@link PublicProjects} is, and the
     * order matters: a creator reading the updates on their own unlaunched campaign is
     * entitled to them, and a campaign that is not publicly visible answers 404 to
     * everybody else. Asking the public question first would have hidden a draft's
     * updates from the person who wrote them.
     *
     * @param viewerId the authenticated caller, or null. This endpoint is public — §10.2
     *     lists it under "Project — public" — so most callers have no identity at all
     * @throws ProjectNotFoundException for a campaign that does not exist and for one
     *     that is not publicly visible, identically, to a caller who is not on its team
     */
    @Transactional(readOnly = true)
    public UpdateTimeline timeline(UUID projectId, UUID viewerId, Integer cursor, Integer limit) {
        boolean team = isTeamMember(projectId, viewerId);
        if (!team) {
            publicProjects.requireVisible(projectId);
        }

        int pageSize = pageSizeOf(limit);
        List<ProjectUpdate> page = new ArrayList<>(updates.page(
                projectId,
                audienceOf(team),
                team ? THE_FAR_FUTURE : clock.instant(),
                cursor == null ? NO_CURSOR : cursor,
                // One more than the page, so "is there another page" is answered by the
                // rows already read rather than by a second count query that could
                // disagree with them.
                Limit.of(pageSize + 1)));

        Integer nextCursor = null;
        if (page.size() > pageSize) {
            page.removeLast();
            nextCursor = page.getLast().getNumber();
        }
        return new UpdateTimeline(page, nextCursor, team);
    }

    /**
     * Which visibilities this caller may read.
     *
     * <p>The one place the audience is decided. Written as a set rather than as a
     * boolean the query interprets, so that a third audience is a value here and not a
     * second branch in the repository.
     */
    private static Set<UpdateVisibility> audienceOf(boolean team) {
        return team ? EVERYTHING : PUBLIC_ONLY;
    }

    /**
     * Whether this caller works on the campaign.
     *
     * <p>Asked of {@link ProjectAccess}, which is the one place in the service where
     * that question is answered, and the refusals are turned into {@code false} rather
     * than propagated: on this path "you are not on the team" is not an error, it is the
     * ordinary case that decides which updates come back.
     */
    private boolean isTeamMember(UUID projectId, UUID accountId) {
        if (accountId == null) {
            return false;
        }
        try {
            access.requireEditable(projectId, accountId);
            return true;
        } catch (ProjectNotFoundException | CapabilityNotGrantedException e) {
            // A stranger, a revoked collaborator, and a collaborator granted only
            // VIEW_FINANCES all land here, and all of them read the campaign as the
            // public does.
            return false;
        }
    }

    /**
     * When this update becomes readable, or a refusal naming the boundary it missed.
     *
     * @param requested what the client asked for, or null for "now"
     * @param newest the update this one follows, if there is one
     */
    private Instant scheduleOf(Instant requested, Optional<ProjectUpdate> newest) {
        Instant now = clock.instant();
        if (requested == null) {
            return now;
        }
        if (requested.isBefore(now.minus(SCHEDULE_GRACE))) {
            throw new UpdateScheduleInvalidException(
                    "An update cannot be published in the past.", requested, now);
        }
        if (requested.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw new UpdateScheduleInvalidException(
                    "An update cannot be scheduled more than a year ahead.", requested, null);
        }

        Instant previous = newest.map(ProjectUpdate::getPublishedAt).orElse(null);
        if (previous != null && requested.isBefore(previous)) {
            // See the class comment: the page is ordered by a number allocated on
            // insert, so an earlier schedule would publish update 6 after update 7.
            throw new UpdateScheduleInvalidException(
                    "An update cannot be scheduled before the one that precedes it.", requested, previous);
        }
        return requested;
    }

    /**
     * Writes the row, flushed rather than merely queued.
     *
     * <p>Flushed for {@code AuditLog}'s reason and for one of this method's own: the
     * unique index on {@code (project_id, number)} is what decides the first-update
     * race, and a violation raised at commit would arrive after the audit row had been
     * written and with a stack trace naming nothing anybody wrote. Here it is a refusal
     * the caller can act on.
     */
    private ProjectUpdate save(UUID projectId, ProjectUpdate update) {
        try {
            return updates.saveAndFlush(update);
        } catch (DataIntegrityViolationException e) {
            throw new UpdateNumberContendedException(projectId, e);
        }
    }

    private static int pageSizeOf(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }
}
