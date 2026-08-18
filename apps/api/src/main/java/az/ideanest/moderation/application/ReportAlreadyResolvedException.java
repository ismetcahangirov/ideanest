package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportState;
import java.util.UUID;

/**
 * Somebody has already decided this report.
 *
 * <p>409 rather than 400: the request was well formed and would have been accepted a
 * moment earlier, and the usual way to reach it is two moderators with the same queue
 * open. Which is exactly why the refusal exists at all — the second decision would
 * otherwise overwrite the first, and the audit row would say the report was upheld
 * by whoever clicked last.
 *
 * <p>Raised from two places in {@code ReportModerationService}, and they are not the
 * same check. One reads the state and refuses; the other is the conditional update
 * coming back having changed nothing, which is the only one that holds when the two
 * moderators arrive together.
 */
public class ReportAlreadyResolvedException extends RuntimeException {

    private final transient UUID reportId;
    private final transient ReportState state;

    public ReportAlreadyResolvedException(UUID reportId, ReportState state) {
        super("Report " + reportId + " is already " + state);
        this.reportId = reportId;
        this.state = state;
    }

    public UUID reportId() {
        return reportId;
    }

    /** What it was decided as. Reported to the client so a stale queue can correct itself. */
    public ReportState state() {
        return state;
    }
}
