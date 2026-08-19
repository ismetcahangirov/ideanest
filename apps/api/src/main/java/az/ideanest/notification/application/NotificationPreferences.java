package az.ideanest.notification.application;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationPreference;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.notification.infrastructure.NotificationPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §10.2's {@code PATCH /v1/me/notification-preferences}: reading the settings page, and
 * changing it.
 *
 * <p>Named by {@code DeliveryPolicy} and {@code NotificationCategory} long before it
 * existed — both say "{@code NotificationPreferences} refuses to store the instruction" —
 * because the refusal was always going to belong here rather than in the policy. The
 * policy answers "what happens", from whatever is stored, and cannot be the thing that
 * tells a person no; it has no caller to tell.
 *
 * <h2>The read is a projection, not the rows</h2>
 *
 * <p><strong>The common case is that this account has no rows at all</strong> —
 * {@code NotificationPreferenceRepository} says so, and {@code DeliveryPolicy}'s class
 * comment explains why nothing is seeded at registration. So the page cannot be the table:
 * it is every (category, channel) §4.10 has, each resolved through the same policy the
 * fan-out uses, with a flag saying whether there is a row behind it. Anything less would
 * be a settings page that renders empty for everybody who has never used it.
 *
 * <h2>Two things are refused, and one is deliberately not</h2>
 *
 * <ul>
 *   <li><strong>A mandatory category</strong> —
 *       {@link PreferenceNotChangeableException}. The instruction is unstorable in effect
 *       either way, and the difference is whether the person is told.
 *   <li><strong>A digest on a channel that cannot digest</strong> —
 *       {@link DeliveryModeUnavailableException}, which argues why this is refused here
 *       and clamped in the policy.
 *   <li><strong>A (category, channel) pair no type in that category has is not
 *       refused.</strong> There is no such pair in §4.10 today —
 *       {@link NotificationType#channelsOf} is a union over the category's types and comes
 *       out as all three columns for all seven categories — and if a later table produced
 *       one, storing the row would be harmless: the fan-out iterates the type's own
 *       channels, so a preference about a column no type has is never consulted. A refusal
 *       for it would be a branch nothing can reach and nothing can test.
 * </ul>
 *
 * <h2>The change is per switch and never wholesale</h2>
 *
 * <p>{@link PreferenceChange} argues the {@code PATCH}. What arrives is the switches
 * somebody touched, each one either updating its row or writing a first one, and every
 * switch not mentioned is left exactly as it was — including left absent, which is not the
 * same as being set to the default.
 *
 * <p><strong>The whole change is one transaction.</strong> A settings page that sent three
 * switches and had the second refused must not leave the first one saved: the person
 * pressed one button, saw one error, and would have no way to know that a third of their
 * change had landed. Every refusal above is therefore raised before anything is flushed,
 * and the rollback is what makes "nothing was saved" true rather than merely intended.
 */
@Service
public class NotificationPreferences {

    private final NotificationPreferenceRepository preferences;
    private final Clock clock;

    public NotificationPreferences(NotificationPreferenceRepository preferences, Clock clock) {
        this.preferences = preferences;
        this.clock = clock;
    }

    /**
     * The whole settings page for one account.
     *
     * <p>Ordered by category and then by channel, both in enum order, so that the page
     * does not reorder itself between requests. §4.10's order is the order
     * {@link NotificationCategory} declares.
     */
    @Transactional(readOnly = true)
    public List<ResolvedPreference> all(UUID userId) {
        Objects.requireNonNull(userId, "A settings page belongs to somebody");
        return resolve(stored(userId));
    }

    /**
     * Applies some instructions and answers with the whole page.
     *
     * <p>The whole page rather than the switches that changed, because a change can move
     * something the caller did not send: a digest is resolved to immediate on a channel
     * that cannot digest, and a client that only got its own switches back would have to
     * reimplement {@code DeliveryPolicy} to know what it now has. It is also what makes
     * this endpoint enough on its own for a page that saves one switch at a time.
     *
     * @param changes what to set. Empty is allowed and changes nothing, which makes this
     *     endpoint a read as well — a client that sends no instructions gets the current
     *     page, and does not have to handle "the request was pointless" as a failure
     * @throws PreferenceNotChangeableException on a mandatory category
     * @throws DeliveryModeUnavailableException on a digest a channel cannot honour
     * @throws PreferenceContendedException when two requests wrote the same switch at once
     */
    @Transactional
    public List<ResolvedPreference> apply(UUID userId, List<PreferenceChange> changes) {
        Objects.requireNonNull(userId, "A settings page belongs to somebody");
        Objects.requireNonNull(changes, "There is some list of instructions, possibly empty");

        for (PreferenceChange change : changes) {
            // Every refusal first, over the whole list, before anything is written. A
            // request refused halfway is a page half saved.
            if (change.category().isMandatory()) {
                throw new PreferenceNotChangeableException(change.category());
            }
            if (change.mode() == DeliveryMode.DIGEST && !change.channel().isDigestible()) {
                throw new DeliveryModeUnavailableException(change.channel(), change.mode());
            }
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (PreferenceChange change : changes) {
            store(userId, change, now);
        }

        try {
            // Flushed here rather than at commit, so that a collision on
            // notification_preferences_key becomes this module's 409 instead of an
            // integrity error escaping past the handler after the response was decided.
            preferences.flush();
        } catch (DataIntegrityViolationException contended) {
            throw new PreferenceContendedException(
                    "Those preferences were changed by another request; try again.", contended);
        }
        return resolve(stored(userId));
    }

    /**
     * One instruction, against whatever is already there.
     *
     * <p><strong>The row is updated rather than replaced, and never deleted.</strong>
     * Setting a switch back to what {@code DeliveryPolicy} would have answered anyway is
     * still a thing the person said, and {@link ResolvedPreference#stored()} reports the
     * difference; deleting the row would turn "they chose immediate" back into "they have
     * never said", which is the distinction {@code DeliveryPolicy}'s class comment exists
     * to preserve.
     */
    private void store(UUID userId, PreferenceChange change, Instant now) {
        preferences
                .findByUserIdAndCategoryAndChannel(userId, change.category(), change.channel())
                .ifPresentOrElse(
                        existing -> existing.changeTo(change.mode(), now),
                        () -> preferences.save(NotificationPreference.of(
                                userId, change.category(), change.channel(), change.mode(), now)));
    }

    /** What this account has said, as a map the resolution can miss in. */
    private Map<CategoryChannel, DeliveryMode> stored(UUID userId) {
        Map<CategoryChannel, DeliveryMode> instructions = new HashMap<>();
        for (NotificationPreference preference : preferences.findByUserId(userId)) {
            instructions.put(
                    new CategoryChannel(preference.getCategory(), preference.getChannel()),
                    preference.getDeliveryMode());
        }
        return instructions;
    }

    /** Every switch §4.10 has, resolved against those instructions. */
    private static List<ResolvedPreference> resolve(Map<CategoryChannel, DeliveryMode> instructions) {
        List<ResolvedPreference> page = new ArrayList<>();
        for (NotificationCategory category : NotificationCategory.values()) {
            for (NotificationChannel channel : NotificationType.channelsOf(category)) {
                page.add(ResolvedPreference.of(
                        category, channel, instructions.get(new CategoryChannel(category, channel))));
            }
        }
        return List.copyOf(page);
    }

    /**
     * The key a preference is looked up by.
     *
     * <p>{@code NotificationFanOut} declares the same record privately for the same
     * lookup, and the duplication is two lines against publishing a type whose only
     * purpose is to be a map key. If a third caller needs it, that is the point at which
     * it earns a file.
     */
    private record CategoryChannel(NotificationCategory category, NotificationChannel channel) {
    }
}
