package az.ideanest.community.application;

/**
 * A page cursor that this endpoint did not produce.
 *
 * <p>A 400. It carries no detail about what was wrong with the value and holds no copy of it —
 * the value came from a request, and echoing a caller-supplied string into a response body and
 * a log line is how a reflected-content problem starts. The client's move is the same whatever
 * was wrong: ask for the first page.
 *
 * <p>Extends {@code RuntimeException} rather than {@code IllegalArgumentException}, which
 * matters here rather than being a style choice: {@code SignalCursor.decode} catches
 * {@code IllegalArgumentException} to turn a malformed value into this, and a subclass of it
 * would be caught by its own handler.
 */
public class InvalidSignalCursorException extends RuntimeException {

    public InvalidSignalCursorException() {
        super("The page cursor is not one this endpoint issued");
    }
}
