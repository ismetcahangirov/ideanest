package az.ideanest.moderation.api;

import az.ideanest.moderation.application.SubmittedReport;
import java.time.Instant;
import java.util.UUID;

/**
 * What the person who made a report is told.
 *
 * <p><strong>Identical whether this request created the report or found the one they
 * already made.</strong> That is deliberate: the reporter did what they meant to do
 * either way, and a response that said "you already reported this" would be a
 * response that trains people to report the same thing from a second account.
 *
 * <p>Nothing about the queue is here — not how many other people reported the same
 * target, not who will read it, not what a moderator eventually wrote.
 * {@code SubmittedReport} has the argument; the short version is that the open-report
 * count is a triage signal for staff and a scoreboard for anybody organising a pile
 * on.
 *
 * @param id so a support conversation has something to name
 * @param target what was reported
 * @param reason the reason on file
 * @param state {@code OPEN} until somebody looks at it
 * @param createdAt when the report on file was made
 */
public record ReportResponse(UUID id, Target target, String reason, String state, Instant createdAt) {

    /**
     * What the report is about.
     *
     * <p>Nested rather than two flat fields, so a client destructures one thing and
     * cannot pair a type with the wrong identifier while rendering a list.
     */
    public record Target(String type, UUID id) {
    }

    static ReportResponse of(SubmittedReport report) {
        return new ReportResponse(
                report.id(),
                new Target(report.targetType().name(), report.targetId()),
                report.reason().name(),
                report.state().name(),
                report.createdAt());
    }
}
