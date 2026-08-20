package az.ideanest.pledge.application;

import az.ideanest.pledge.infrastructure.BackerSegmentRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.7's CD-10: saving the filters a campaign works by.
 *
 * <h2>The same capability as the report itself</h2>
 *
 * <p>{@link ProjectCapability#VIEW_FINANCES}, on reading and on writing alike. Naming a
 * filter is not a more privileged act than running it — the segment reveals nothing the
 * report does not — and a second, narrower capability would mean a collaborator who can
 * read every backer's email address cannot save the search that found them.
 *
 * <h2>The name is the only thing a creator can collide on</h2>
 *
 * <p>V31's unique index compares names folded and trimmed, and this service does not check
 * first. It inserts and translates the refusal, because a read-then-write loses the race
 * between two tabs and produces the duplicate the index exists to prevent.
 */
@Service
public class BackerSegmentService {

    /**
     * How many saved segments a campaign may have.
     *
     * <p>In the service rather than in the schema, because it is a judgement about a list
     * a person reads rather than an invariant about a row. See
     * {@link TooManyBackerSegmentsException}.
     */
    public static final int MAX_SEGMENTS = 40;

    private final ProjectAuthorisation projects;
    private final BackerSegmentRepository segments;
    private final Clock clock;

    public BackerSegmentService(ProjectAuthorisation projects, BackerSegmentRepository segments, Clock clock) {
        this.projects = projects;
        this.segments = segments;
        this.clock = clock;
    }

    /** Every segment saved against this campaign, newest first. */
    @Transactional(readOnly = true)
    public List<BackerSegment> of(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);
        return segments.of(projectId);
    }

    /**
     * The filter a saved segment stands for.
     *
     * <p>Used by the report and the export when a caller asks for a segment by identifier
     * rather than describing the filter again. That indirection is the point of saving one:
     * a segment whose definition is edited changes what the export produces, which is what
     * a creator means by "our German backers".
     *
     * @throws BackerSegmentNotFoundException when there is no such segment on this campaign
     */
    @Transactional(readOnly = true)
    public BackerFilter filterOf(UUID projectId, UUID accountId, UUID segmentId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);
        return segments.find(projectId, segmentId)
                .map(BackerSegment::filter)
                .orElseThrow(() -> new BackerSegmentNotFoundException(segmentId));
    }

    /**
     * Saves a new segment.
     *
     * @throws BackerSegmentNameTakenException when the campaign already has one by that
     *     name, folded
     * @throws TooManyBackerSegmentsException when the campaign is already at
     *     {@link #MAX_SEGMENTS}
     */
    @Transactional
    public BackerSegment save(UUID projectId, UUID accountId, String name, BackerFilter filter) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        if (segments.countOf(projectId) >= MAX_SEGMENTS) {
            // Checked rather than constrained, so it is checked before the insert and can
            // lose a race with a concurrent save. The consequence of losing it is a
            // campaign with one segment more than the limit, which is a list one row
            // longer than intended -- not a class of problem worth a table lock.
            throw new TooManyBackerSegmentsException(MAX_SEGMENTS);
        }

        Instant now = now();
        BackerSegment segment =
                new BackerSegment(Identifiers.newIdentifier(), projectId, name.strip(), filter, accountId, now, now);
        try {
            return segments.save(segment);
        } catch (DuplicateKeyException collision) {
            throw new BackerSegmentNameTakenException(segment.name());
        }
    }

    /**
     * Replaces a segment's name and filter.
     *
     * @throws BackerSegmentNotFoundException when there is no such segment on this campaign
     * @throws BackerSegmentNameTakenException when another segment on the campaign already
     *     has that name
     */
    @Transactional
    public BackerSegment replace(UUID projectId, UUID accountId, UUID segmentId, String name, BackerFilter filter) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        BackerSegment existing =
                segments.find(projectId, segmentId).orElseThrow(() -> new BackerSegmentNotFoundException(segmentId));

        // createdBy and createdAt are the existing row's: an edit does not make the editor
        // the author, and "who set this up" would otherwise be answered by whoever last
        // touched it.
        BackerSegment replacement = new BackerSegment(
                existing.id(),
                projectId,
                name.strip(),
                filter,
                existing.createdBy(),
                existing.createdAt(),
                now());
        try {
            if (!segments.replace(replacement)) {
                throw new BackerSegmentNotFoundException(segmentId);
            }
        } catch (DuplicateKeyException collision) {
            throw new BackerSegmentNameTakenException(replacement.name());
        }
        return replacement;
    }

    /**
     * Forgets a segment.
     *
     * <p>A hard delete, unlike most of this platform's removals. There is nothing to
     * retain: the row holds no personal data and no money, and a soft-deleted filter would
     * keep the name reserved against the unique index — so a creator who deleted "Germany"
     * could not make a new one.
     *
     * @throws BackerSegmentNotFoundException when there is no such segment on this campaign
     */
    @Transactional
    public void delete(UUID projectId, UUID accountId, UUID segmentId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        if (!segments.delete(projectId, segmentId)) {
            throw new BackerSegmentNotFoundException(segmentId);
        }
    }

    /** Truncated to microseconds, which is what PostgreSQL stores and what survives a round trip. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
