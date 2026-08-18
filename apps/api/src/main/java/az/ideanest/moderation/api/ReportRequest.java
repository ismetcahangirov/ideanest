package az.ideanest.moderation.api;

import az.ideanest.moderation.domain.ContentReport;
import az.ideanest.moderation.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What somebody reporting something sends.
 *
 * <p><strong>The target is not in the body.</strong> It is the path, and the kind is
 * decided by which endpoint was called — so a client cannot report a campaign by
 * sending {@code "targetType": "USER"} with a campaign's identifier, and the two
 * routes cannot be made to disagree about what they are reporting.
 *
 * @param reason from §5.4's taxonomy. Required; a report with no reason is a row a
 *     moderator has to open to find out what it is about. An unknown value is a 400
 *     from the message converter rather than a silent {@code OTHER}
 * @param detail what the reporter wants to say. Optional, except for
 *     {@link ReportReason#OTHER} — see {@code ReportingService}. Bounded at what V23
 *     accepts, so that the refusal is a field-level 400 naming {@code detail} rather
 *     than a constraint violation served as a 500
 */
public record ReportRequest(
        @NotNull(message = "A report says why") ReportReason reason,
        @Size(
                        max = ContentReport.DETAIL_MAX_LENGTH,
                        message = "A report may not exceed 2000 characters")
                String detail) {
}
