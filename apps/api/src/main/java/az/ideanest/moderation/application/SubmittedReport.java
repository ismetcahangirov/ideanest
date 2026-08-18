package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ContentReport;
import az.ideanest.moderation.domain.ReportReason;
import az.ideanest.moderation.domain.ReportState;
import az.ideanest.moderation.domain.ReportTargetType;
import java.time.Instant;
import java.util.UUID;

/**
 * A report as the person who made it gets it back.
 *
 * <p>Deliberately thin. The reporter is told that the platform has their complaint
 * and nothing about what happens next: not who will read it, not how many other
 * people have reported the same thing, and not — once it is decided — what the
 * moderator wrote. The open-report count in particular is a triage signal for staff
 * and would be a scoreboard for anybody trying to work out whether a brigading
 * campaign is working.
 *
 * @param id the report's identifier, so a support conversation has something to name
 * @param targetType what they reported
 * @param targetId which one
 * @param reason the reason on file, which for a repeat submission is the reason of
 *     the report that already existed rather than the one just sent — the row is
 *     never rewritten, and saying otherwise would be a response that disagrees with
 *     the queue
 * @param state where it is
 * @param createdAt when the report on file was made, which again is the earlier one
 *     for a repeat
 * @param created whether this call is what created it. <strong>Not sent to the
 *     client</strong>; it decides the log line and exists so that the endpoint does
 *     not have to compare timestamps to find out whether anything happened
 */
public record SubmittedReport(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        ReportReason reason,
        ReportState state,
        Instant createdAt,
        boolean created) {

    static SubmittedReport of(ContentReport report, boolean created) {
        return new SubmittedReport(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getState(),
                report.getCreatedAt(),
                created);
    }
}
