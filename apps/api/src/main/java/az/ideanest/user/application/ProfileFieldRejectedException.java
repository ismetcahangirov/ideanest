package az.ideanest.user.application;

/**
 * One field of a profile edit was refused — §4.2's P-01 to P-03 (#276).
 *
 * <p>Answered as 400 with {@code code: PROFILE_FIELD_INVALID} and the field name in
 * {@code meta}, so the editor can put the message beside the input that caused it rather
 * than in a banner at the top of a form. Deliberately the same shape as
 * {@code ProjectFieldRejectedException}: a client that already handles one needs no second
 * branch, and two shapes for "this field is wrong" would be two for no reason.
 *
 * <p><strong>Refusing rather than normalising is the rule here, not a preference.</strong>
 * An endpoint that quietly dropped a location slug it did not recognise, or silently
 * rewrote {@code http://} to {@code https://}, would report success for a save that did not
 * happen — and the person would find out when they next looked at their own page, which is
 * the worst time and the hardest thing to report. The one normalisation this service does
 * perform is stripping surrounding whitespace, which changes nothing anybody meant.
 *
 * <p>Every rule enforced through this exception is also a constraint in V2 or V46. That is
 * not duplication with a shrug: the constraint holds against a support query and a data
 * fix, and this turns the same rule into a 400 the client can act on instead of a
 * constraint violation surfacing as a 500.
 */
public class ProfileFieldRejectedException extends RuntimeException {

    private final String field;

    /**
     * @param field the JSON field name, as the client sent it — {@code websiteUrl}, not
     *     {@code website_url}. A client cannot highlight an input it has no name for
     * @param message written for the person editing their own profile, so it says what is
     *     allowed rather than which constraint was violated
     */
    public ProfileFieldRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
