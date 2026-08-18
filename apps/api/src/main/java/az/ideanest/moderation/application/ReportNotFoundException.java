package az.ideanest.moderation.application;

import java.util.UUID;

/**
 * No report by that identifier.
 *
 * <p>404, and unlike most 404s in this service it is not hiding anything: the caller
 * has already been established as platform staff by the time this can be thrown, so
 * there is nothing left to be evasive about. It means what it says.
 */
public class ReportNotFoundException extends RuntimeException {

    private final transient UUID reportId;

    public ReportNotFoundException(UUID reportId) {
        super("No report " + reportId);
        this.reportId = reportId;
    }

    public UUID reportId() {
        return reportId;
    }
}
