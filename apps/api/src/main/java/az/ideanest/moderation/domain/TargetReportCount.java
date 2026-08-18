package az.ideanest.moderation.domain;

import java.util.UUID;

/**
 * How many people currently have an open complaint about one thing.
 *
 * <p>The queue's only triage signal. "This campaign has been reported" and "this
 * campaign has been reported by fourteen people" are different facts, and a
 * moderator with a hundred rows in front of them is working from the second one.
 *
 * <p>A constructor expression rather than a projection interface because it is
 * assembled by a {@code GROUP BY} in {@code ContentReportRepository}, which returns
 * three scalars and no entity to project from.
 *
 * @param targetType what kind of thing
 * @param targetId which one
 * @param openReports how many open reports name it, counting this one
 */
public record TargetReportCount(ReportTargetType targetType, UUID targetId, long openReports) {
}
