package az.ideanest.pledge.application;

/**
 * This campaign has as many saved segments as the report will hold.
 *
 * <p>A 409, and a limit in the service rather than in the schema: it is a product judgment
 * about a list a person reads, not an invariant about what a row may contain, and V31's
 * table would be no less correct with a thousand rows in it.
 *
 * <p>The bound exists because the segment list is loaded whole on every visit to the
 * report and is rendered as a set of chips. A campaign with two hundred saved filters has
 * a navigation problem that no amount of scrolling fixes, and the limit is where that
 * becomes visible rather than gradual.
 */
public class TooManyBackerSegmentsException extends RuntimeException {

    public TooManyBackerSegmentsException(int limit) {
        super("A campaign holds at most " + limit + " saved segments. Delete one you no longer use.");
    }
}
