package az.ideanest.project.application;

/**
 * A page cursor this endpoint did not issue.
 *
 * <p>Carries nothing. It does not echo the value, and it does not say which part of it was
 * wrong: the client's next move is the same however it failed — ask for the first page —
 * and naming the failing half would tell whoever is probing how the value is built.
 *
 * <p>Refused rather than ignored. Serving the first page for a corrupt cursor would make a
 * client that is paging wrongly look like one that has reached the end, and the reader
 * would silently see the top of a creator's list again instead of the rest of it.
 * {@code InvalidSignalCursorException} makes the same choice for the same reason.
 */
public class InvalidProfileCursorException extends RuntimeException {

    public InvalidProfileCursorException() {
        super("That page cursor is not one this endpoint issued");
    }
}
