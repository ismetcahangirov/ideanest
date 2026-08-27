package az.ideanest.fx.application;

/**
 * A rate source that could not be asked — issue #327.
 *
 * <p>Distinct from "answered and published nothing", which is an ordinary Sunday. This is an
 * outage, a rewritten document, or a network that is not there, and the refresh logs it and
 * keeps whatever it already had.
 *
 * <p>It is never surfaced to a reader. A display currency whose rate could not be refreshed
 * shows the last one inside {@code ideanest.fx.max-age} and then stops being offered — see
 * the package note on degrading to absence rather than to a guess.
 */
public class RateSourceUnavailableException extends RuntimeException {

    public RateSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public RateSourceUnavailableException(String message) {
        super(message);
    }
}
