package az.ideanest.pledgemanager.domain;

import java.util.Objects;

/**
 * What a backer needs in order to follow a parcel — §4.8's PM-20 and PM-21.
 *
 * <p>A value rather than three columns passed around together, because the three are
 * only meaningful as a set and the rule between them is not obvious: <strong>a
 * tracking number without a carrier is refused.</strong> A bare number is a string
 * nobody can look up, and a backer shown one reads it as something they can act on —
 * they then spend an evening pasting it into the wrong carrier's website.
 *
 * <p>The same three bounds V38 puts on the columns are checked here, so a bad import
 * row is refused with the field's name rather than by a constraint violation whose
 * message is a constraint name. The database keeps them anyway: this class is what a
 * creator reads, and the check constraint is what makes it true of every row however
 * it was written.
 *
 * <p><strong>{@link #none()} is a first-class value, not null.</strong> A campaign
 * that hands parcels to backers at an event ships without tracking, and so does one
 * whose reward is digital; both are ordinary, and modelling them as an absent
 * {@code Tracking} would put a null check in every caller.
 *
 * @param carrier who is carrying it, or null. Free text — V38 says why a closed list
 *     would be wrong
 * @param number the carrier's reference, or null
 * @param url where the backer clicks, or null. {@code https} only, because a link a
 *     creator typed is one several thousand backers follow
 */
public record Tracking(String carrier, String number, String url) {

    private static final int MAX_CARRIER = 60;

    private static final int MAX_NUMBER = 64;

    private static final int MAX_URL = 300;

    private static final Tracking NONE = new Tracking(null, null, null);

    public Tracking {
        carrier = trimmedToNull(carrier);
        number = trimmedToNull(number);
        url = trimmedToNull(url);

        require(carrier == null || carrier.length() <= MAX_CARRIER, "carrier", "A carrier name is at most 60 characters.");
        require(number == null || number.length() <= MAX_NUMBER, "trackingNumber", "A tracking number is at most 64 characters.");
        require(url == null || url.length() <= MAX_URL, "trackingUrl", "A tracking link is at most 300 characters.");
        require(
                url == null || url.startsWith("https://"),
                "trackingUrl",
                "A tracking link must be an https:// address.");
        require(
                number == null || carrier != null,
                "carrier",
                "A tracking number needs the carrier it belongs to, or nobody can look it up.");
    }

    /** A parcel with nothing to follow. See the class comment: this is ordinary. */
    public static Tracking none() {
        return NONE;
    }

    /** Whether anything here is worth showing a backer. */
    public boolean isEmpty() {
        return Objects.equals(this, NONE);
    }

    private static String trimmedToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new TrackingInvalidException(field, message);
        }
    }
}
