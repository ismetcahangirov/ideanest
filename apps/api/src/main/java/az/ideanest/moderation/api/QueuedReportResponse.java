package az.ideanest.moderation.api;

import az.ideanest.moderation.application.QueuedReport;
import java.time.Instant;
import java.util.UUID;

/**
 * A report as the queue shows it to platform staff.
 *
 * <p>Everything {@link ReportResponse} withholds. The audience is the one the
 * withholding was protecting against, and each of the three additions is a question a
 * moderator has to answer before they can act:
 *
 * <ul>
 *   <li><strong>{@code openReportsOnTarget}</strong> — one complaint about a campaign
 *       and fourteen are different situations, and the second is the one that gets
 *       looked at first.
 *   <li><strong>{@code reporterId}</strong> — a report is an accusation. A queue that
 *       anonymised the accuser would make "this account has reported forty campaigns
 *       this week" unaskable, and that question is what catches the abuse of this
 *       feature.
 *   <li><strong>{@code detail}</strong> — what the reporter actually wrote, which for
 *       {@code OTHER} is the entire content of the report.
 * </ul>
 *
 * @param resolution null while the report is open, which is what a client renders the
 *     two buttons from rather than comparing state strings
 */
public record QueuedReportResponse(
        UUID id,
        ReportResponse.Target target,
        long openReportsOnTarget,
        UUID reporterId,
        String reason,
        String detail,
        String state,
        Instant createdAt,
        Resolution resolution) {

    /**
     * Who decided it, when, and what they wrote.
     *
     * <p>Nested and absent rather than three nullable fields, so "has this been
     * decided" is one check. V23 refuses a row with only some of the three, so a
     * partially populated object is not a state that can arrive here.
     */
    public record Resolution(UUID moderatorId, Instant at, String note) {
    }

    static QueuedReportResponse of(QueuedReport report) {
        return new QueuedReportResponse(
                report.id(),
                new ReportResponse.Target(report.targetType().name(), report.targetId()),
                report.openReportsOnTarget(),
                report.reporterId(),
                report.reason().name(),
                report.detail(),
                report.state().name(),
                report.createdAt(),
                report.resolvedBy() == null
                        ? null
                        : new Resolution(report.resolvedBy(), report.resolvedAt(), report.resolutionNote()));
    }
}
