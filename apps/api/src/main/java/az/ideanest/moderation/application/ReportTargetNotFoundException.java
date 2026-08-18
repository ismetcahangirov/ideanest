package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportTargetType;
import java.util.UUID;

/**
 * There is nothing at that identifier for this caller to report.
 *
 * <p>404, and the same 404 whether the thing never existed, was deleted, or is a
 * campaign the public cannot see. {@code PublicProjects} draws that line and this
 * inherits it: a draft is confidential and a suspended campaign is one trust and
 * safety has already stopped, so confirming either exists would turn the report
 * endpoint into the oracle every other public endpoint refuses to be.
 */
public class ReportTargetNotFoundException extends RuntimeException {

    private final transient ReportTargetType targetType;
    private final transient UUID targetId;

    public ReportTargetNotFoundException(ReportTargetType targetType, UUID targetId) {
        super("No " + targetType + " " + targetId + " to report");
        this.targetType = targetType;
        this.targetId = targetId;
    }

    public ReportTargetType targetType() {
        return targetType;
    }

    public UUID targetId() {
        return targetId;
    }
}
