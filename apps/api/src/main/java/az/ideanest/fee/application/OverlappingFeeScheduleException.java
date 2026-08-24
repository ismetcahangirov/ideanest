package az.ideanest.fee.application;

import az.ideanest.fee.domain.FeeScope;
import java.util.UUID;

/**
 * Two administrators replaced the same scope's schedule at once — #311.
 *
 * <p>409, because nothing is wrong with the request: it was correct when it was composed
 * and another one got there first. The console reloads and shows what is now in force,
 * which is the only sensible next step — re-sending would open a third window on top of
 * a schedule the reader has not seen.
 *
 * <p>Detected by V49's partial unique index rather than by a lock. {@code FeeSchedules}
 * has the argument: this screen is used a handful of times a year.
 */
public class OverlappingFeeScheduleException extends RuntimeException {

    public OverlappingFeeScheduleException(FeeScope scope, UUID scopeRef) {
        super("A schedule for " + scope + " " + scopeRef + " was opened by another request");
    }
}
