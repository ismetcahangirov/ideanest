package az.ideanest.reward.application;

/**
 * A set of shipping regions the platform will not store — §4.8's PM-13 (#77).
 *
 * <p>Every case it carries is one V37 also refuses, and the duplication is the
 * point: the database is what makes a bad row impossible, and this is what makes the
 * refusal a sentence a creator can act on rather than a constraint name. A region
 * with no destinations, two regions folding to one name, and one country in two
 * regions are all mistakes a rate editor can highlight.
 *
 * <p>A 400 rather than a 409: nothing about the campaign has changed underneath the
 * caller, the body simply does not describe a set of regions.
 */
public class ShippingZoneInvalidException extends RuntimeException {

    public ShippingZoneInvalidException(String message) {
        super(message);
    }
}
