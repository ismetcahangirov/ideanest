package az.ideanest.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One complaint, by one account, about one thing.
 *
 * <p><strong>There is no {@code resolve} method on this class, and that is the
 * design.</strong> Deciding a report is
 * {@code ContentReportRepository#resolveIfOpen}, a conditional update, and never a
 * loaded row with a field assigned to it: two moderators opening the same queue —
 * which is the normal state of affairs the moment there is more than one — would
 * both read {@code OPEN}, both decide, and the second decision would overwrite the
 * first with no trace that there had been one. Only the database can break that tie,
 * and it can only break it if the condition is part of the statement.
 * {@code ReminderRepository#claim} reaches the same conclusion for the same reason.
 *
 * <p><strong>There is no {@code create} method either.</strong> A report is inserted
 * by {@code ContentReportRepository#insertIfAbsent}, an {@code ON CONFLICT DO
 * NOTHING} against V23's partial unique index, because duplicate suppression is half
 * the feature and a read-then-write check in Java loses the race between two taps.
 * So this entity is what a report is <em>read</em> as, and the writes are two
 * statements that name their own conditions.
 *
 * <p>The target, the reporter and the moderator are identifiers rather than
 * associations. The reporter and the moderator are {@code users} rows, which belong
 * to another module — a {@code @ManyToOne} across that boundary is the coupling
 * {@code ModuleBoundaryTests} exists to prevent — and the target is not one table at
 * all, which is the argument V23's header makes at length.
 */
@Entity
@Table(name = "content_reports")
public class ContentReport {

    /** What V23's {@code content_reports_detail_length} allows. */
    public static final int DETAIL_MAX_LENGTH = 2000;

    /** What V23's {@code content_reports_resolution_note_length} allows. */
    public static final int RESOLUTION_NOTE_MAX_LENGTH = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    private ReportReason reason;

    /** What the reporter wrote. Null unless they wrote something; required for {@link ReportReason#OTHER}. */
    @Column(name = "detail", updatable = false)
    private String detail;

    /**
     * Not updatable through the entity. See the class comment: the only correct way
     * to write this column is the conditional update, so the field exists to be read
     * back after that statement has run.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, insertable = false, updatable = false)
    private ReportState state;

    @Column(name = "resolved_by", insertable = false, updatable = false)
    private UUID resolvedBy;

    @Column(name = "resolved_at", insertable = false, updatable = false)
    private Instant resolvedAt;

    @Column(name = "resolution_note", insertable = false, updatable = false)
    private String resolutionNote;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ContentReport() {
        // JPA.
    }

    /** Whether anybody has decided this yet. */
    public boolean isOpen() {
        return state == ReportState.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public ReportTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public ReportState getState() {
        return state;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ContentReport report && Objects.equals(id, report.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No detail and no note. Both are free text written by a person about
        // another person, and this lands in logs, which §17.4 keeps that out of.
        return "ContentReport[id=" + id + ", target=" + targetType + ":" + targetId + ", state=" + state + "]";
    }
}
