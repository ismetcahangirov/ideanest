package az.ideanest.notification.application;

import az.ideanest.notification.domain.NotificationCategory;
import java.util.Objects;

/**
 * Somebody tried to set a preference on a category that cannot have one.
 *
 * <p>{@code NotificationCategory.SECURITY} today, and the argument is that constant's:
 * the message exists to tell somebody that another person is in their account, and the
 * first thing that other person would do is silence it. A preference that can be set by
 * whoever stole the session is not a preference the account holder has.
 *
 * <p><strong>Refused rather than accepted and ignored.</strong> The instruction is
 * unstorable either way — {@code DeliveryPolicy} overrules a stored value on a mandatory
 * category, so a row for one changes nothing — and the difference is whether the person
 * is told. Accepting it would leave somebody believing they had turned off an alert they
 * will keep receiving, which is the same class of failure as offering a digest that
 * delivers nothing.
 *
 * <p>Any mode is refused, including {@code IMMEDIATE}, which happens to agree with the
 * policy. The refusal is about the category being outside the caller's gift, not about
 * this particular value being wrong.
 */
public class PreferenceNotChangeableException extends RuntimeException {

    private final NotificationCategory category;

    public PreferenceNotChangeableException(NotificationCategory category) {
        super("Notifications in the " + Objects.requireNonNull(category, "A refusal is about a category")
                + " category cannot be turned off or delayed.");
        this.category = category;
    }

    /** The category that refused. Travels to the client so it can disable the control. */
    public NotificationCategory category() {
        return category;
    }
}
