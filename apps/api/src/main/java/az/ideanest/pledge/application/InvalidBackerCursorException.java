package az.ideanest.pledge.application;

/**
 * A page cursor neither of the backer lists issued.
 *
 * <p>Carries nothing: not the value, not which half of it failed. The client's next move is
 * the same however it broke — ask for the first page — and naming the failing half would
 * tell whoever is probing how the value is built.
 *
 * <p>Refused rather than ignored. Serving the first page for a corrupt cursor would make a
 * client that is paging wrongly look like one that has reached the end, and a backer would
 * see the top of their pledge list again instead of the rest of it.
 */
public class InvalidBackerCursorException extends RuntimeException {

    public InvalidBackerCursorException() {
        super("That page cursor is not one this endpoint issued");
    }
}
