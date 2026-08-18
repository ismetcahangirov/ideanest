package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportTargetType;

/**
 * That kind of thing cannot be reported yet.
 *
 * <p>{@link ReportTargetType#COMMENT} and {@link ReportTargetType#PROJECT_UPDATE}
 * are in the taxonomy and in V23's check constraint, and neither table exists — so
 * an identifier cannot be validated, a moderator cannot be shown what was reported,
 * and §10.2's {@code POST /v1/comments/{id}/report} is not published by this
 * release.
 *
 * <p><strong>Unreachable through the API today, and it exists anyway.</strong> No
 * route produces either type: the two controllers name the type themselves rather
 * than taking it from the request, which is what makes an invalid one unspellable
 * by a client. What this guards is the next path somebody adds — a report accepted
 * for a target nobody can look at is a reporter shown a success for a complaint that
 * will never be read, and that is the failure worth making loud rather than silent.
 */
public class UnsupportedReportTargetException extends RuntimeException {

    private final transient ReportTargetType targetType;

    public UnsupportedReportTargetException(ReportTargetType targetType) {
        super("Reports about a " + targetType + " cannot be accepted yet");
        this.targetType = targetType;
    }

    public ReportTargetType targetType() {
        return targetType;
    }
}
