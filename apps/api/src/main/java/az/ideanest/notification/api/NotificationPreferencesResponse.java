package az.ideanest.notification.api;

import az.ideanest.notification.application.ResolvedPreference;
import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The whole settings page: every switch §4.10 has, resolved.
 *
 * <p><strong>Every switch, always, and never only the stored ones.</strong> The common case
 * is an account with no rows at all — {@code NotificationPreferenceRepository} says so, and
 * {@code DeliveryPolicy} explains why nothing is seeded at registration — so a response
 * that listed the table would be empty for everybody who has never opened the page, and a
 * client would have to reimplement the defaults to draw anything.
 *
 * <p>Flat rather than nested by category. A client that wants it grouped groups it in one
 * pass; a client that wants a single switch finds it without walking two levels; and the
 * shape is the same as what {@code PATCH} accepts, which means a page can send back a row
 * it was given without transposing it.
 *
 * <p>The order is §4.10's — category in the order {@link NotificationCategory} declares
 * them, then channel — and it is stable between requests, so a page does not reorder itself
 * under somebody's cursor while they are reading it.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationPreferencesResponse(List<Preference> preferences) {

    /**
     * One switch.
     *
     * @param mode what happens today. The resolved answer, not the stored value —
     *     {@code ResolvedPreference} argues why the difference matters
     * @param stored whether the account has ever said anything about this switch. Lets a
     *     client show a default as a default rather than as a choice somebody made
     * @param changeable false on a mandatory category, so the control is drawn disabled
     *     rather than left out. A security alert that cannot be silenced is something the
     *     account holder should be able to see the reason for
     * @param digestOffered whether {@link DeliveryMode#DIGEST} is one of the choices here.
     *     On the row so the control is built from the response instead of the client
     *     reimplementing §4.10's rules and then drifting from them
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preference(
            NotificationCategory category,
            NotificationChannel channel,
            DeliveryMode mode,
            boolean stored,
            boolean changeable,
            boolean digestOffered) {
    }

    public static NotificationPreferencesResponse of(List<ResolvedPreference> preferences) {
        return new NotificationPreferencesResponse(
                preferences.stream()
                        .map(preference -> new Preference(
                                preference.category(),
                                preference.channel(),
                                preference.mode(),
                                preference.stored(),
                                preference.changeable(),
                                preference.digestOffered()))
                        .toList());
    }
}
