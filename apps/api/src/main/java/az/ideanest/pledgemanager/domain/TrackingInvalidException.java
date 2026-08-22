package az.ideanest.pledgemanager.domain;

/**
 * Tracking details the platform will not store — §4.8's PM-20.
 *
 * <p>Carries the field, like {@link AddressInvalidException} and for the same reason:
 * the caller is usually a bulk import of several thousand rows, and "row 412 is
 * invalid" sends a creator to read a row rather than to fix a column.
 *
 * <p>The message may quote a bound and never quotes the value. A tracking number is
 * not personal data in the way an address is, but a message built from an imported
 * cell is a message built from a file this platform did not write.
 */
public class TrackingInvalidException extends RuntimeException {

    private final String field;

    public TrackingInvalidException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** {@code carrier}, {@code trackingNumber} or {@code trackingUrl}, as a client names it. */
    public String field() {
        return field;
    }
}
