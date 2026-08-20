package az.ideanest.pledge.application;

/**
 * This campaign already has a segment by that name.
 *
 * <p>A 409. Names are compared folded and trimmed, by the unique index V31 creates, so
 * "Germany" and "germany " collide — which is the intended answer: the second one is
 * somebody who forgot they had made the first, and silently keeping both would leave a
 * creator choosing between two identical-looking rows.
 *
 * <p>Raised from the constraint violation rather than from a prior read. A read-then-write
 * loses the race between two tabs, and this is the class of check where the database is
 * the only place the answer cannot be stale.
 */
public class BackerSegmentNameTakenException extends RuntimeException {

    public BackerSegmentNameTakenException(String name) {
        super("This campaign already has a segment called \"" + name + "\".");
    }
}
