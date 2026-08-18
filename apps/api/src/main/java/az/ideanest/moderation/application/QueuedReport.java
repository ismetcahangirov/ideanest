package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ContentReport;
import az.ideanest.moderation.domain.ReportReason;
import az.ideanest.moderation.domain.ReportState;
import az.ideanest.moderation.domain.ReportTargetType;
import java.time.Instant;
import java.util.UUID;

/**
 * A report as a moderator reads it.
 *
 * <p>Everything {@code SubmittedReport} withholds, because the audience is the one
 * the withholding was protecting against being seen by. In particular it names the
 * reporter: a report is an accusation, and a queue that anonymised the accuser would
 * make "this account has reported forty campaigns this week" unaskable, which is the
 * question that catches the abuse of this feature.
 *
 * @param id the report
 * @param targetType what was reported
 * @param targetId which one
 * @param openReportsOnTarget how many people currently have an open complaint about
 *     that same thing, counting this one. The queue's whole triage signal; see
 *     {@code TargetReportCount}
 * @param reporterId who reported it
 * @param reason from §5.4's taxonomy
 * @param detail what the reporter wrote, or null
 * @param state where the report is
 * @param createdAt when it was made
 * @param resolvedBy which moderator decided it, or null while it is open
 * @param resolvedAt when, or null
 * @param resolutionNote what they wrote for the next moderator, or null
 */
public record QueuedReport(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        long openReportsOnTarget,
        UUID reporterId,
        ReportReason reason,
        String detail,
        ReportState state,
        Instant createdAt,
        UUID resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {

    static QueuedReport of(ContentReport report, long openReportsOnTarget) {
        return new QueuedReport(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                openReportsOnTarget,
                report.getReporterId(),
                report.getReason(),
                report.getDetail(),
                report.getState(),
                report.getCreatedAt(),
                report.getResolvedBy(),
                report.getResolvedAt(),
                report.getResolutionNote());
    }
}
