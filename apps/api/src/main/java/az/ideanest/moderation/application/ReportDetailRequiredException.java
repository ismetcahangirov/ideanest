package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportReason;

/**
 * The reason chosen was {@link ReportReason#OTHER} and the reporter wrote nothing.
 *
 * <p>A report filed under "other" with no text cannot be acted on, and a queue of
 * them is a queue that gets ignored — which costs the reports that <em>could</em>
 * have been acted on, not only these. V23's {@code content_reports_other_says_what}
 * holds the same rule against a bulk import and a support script.
 */
public class ReportDetailRequiredException extends RuntimeException {

    private final transient ReportReason reason;

    public ReportDetailRequiredException(ReportReason reason) {
        super("A report of " + reason + " has to say what");
        this.reason = reason;
    }

    public ReportReason reason() {
        return reason;
    }
}
