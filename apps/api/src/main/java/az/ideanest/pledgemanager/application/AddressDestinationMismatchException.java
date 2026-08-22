package az.ideanest.pledgemanager.application;

/**
 * An address in a country the pledge was not quoted for.
 *
 * <p>§4.5's PL-05 prices a parcel against {@code pledges.shipping_country}, frozen at
 * checkout. Accepting an address elsewhere would charge the backer for one destination
 * and post to another, and the creator would discover the difference on a carrier
 * invoice rather than on a screen.
 *
 * <p>422 rather than 400: the body is well-formed and the address is a valid address.
 * What is wrong is the relationship between it and the pledge, which is exactly the
 * distinction that status exists for.
 */
public class AddressDestinationMismatchException extends RuntimeException {

    private final String quoted;
    private final String given;

    public AddressDestinationMismatchException(String quoted, String given) {
        super("This pledge was quoted for " + quoted + " and the address is in " + given);
        this.quoted = quoted;
        this.given = given;
    }

    /** What shipping was priced against. */
    public String quoted() {
        return quoted;
    }

    /** What the backer typed. */
    public String given() {
        return given;
    }
}
