package az.ideanest.notification.domain;

import java.util.Objects;

/**
 * What happens to somebody who has never opened the settings page — and the rules that
 * hold whatever they did there.
 *
 * <p><strong>This class is the policy. {@code notification_preferences} is the
 * data.</strong> That separation is the whole reason there is no seed in V26 and no
 * {@code DEFAULT} in it that encodes a product decision:
 *
 * <ul>
 *   <li>Seeding a row per (user, category, channel) at registration would write
 *       twenty-one rows for every account before anybody expressed a preference about
 *       any of them, and it would freeze today's default into every existing account —
 *       so changing the default would become a backfill instead of a deployment.
 *   <li>It would also make the two indistinguishable afterwards. "IMMEDIATE because we
 *       chose it for them" and "IMMEDIATE because they chose it" are different facts,
 *       and only the second may survive a change of policy.
 * </ul>
 *
 * <p>So the absence of a row <em>is</em> the answer "no preference expressed", and this
 * is where the answer to "then what" lives, in one place, arguable.
 *
 * <p>A static holder rather than a bean, deliberately: it decides nothing from
 * configuration and reads nothing, so it is testable without a context and cannot
 * acquire a dependency that would make it depend on one.
 */
public final class DeliveryPolicy {

    private DeliveryPolicy() {
    }

    /**
     * The default for a channel a type has and the recipient has said nothing about.
     *
     * <p><strong>{@link DeliveryMode#IMMEDIATE}, for every category and every
     * channel</strong>, and the argument is §4.10 itself: that table is the platform's
     * statement of what it tells people, and a ✅ that arrived switched off would make
     * it a description of nothing. Everything in it is transactional — a pledge
     * somebody made, a card that was refused, a campaign they backed closing — rather
     * than marketing, and the failure directions are not symmetrical. Defaulted on, the
     * cost is a message somebody did not want and can turn off in one place. Defaulted
     * off, the cost is a backer who never finds out their payment failed, which is a
     * pledge lost for a reason nobody can see.
     *
     * <p><strong>Never {@link DeliveryMode#DIGEST}.</strong> Nothing drains a held
     * notification yet — see that constant — so defaulting anybody to it would default
     * them to silence.
     */
    public static DeliveryMode defaultFor(NotificationType type, NotificationChannel channel) {
        Objects.requireNonNull(type, "A default is for some kind of notification");
        Objects.requireNonNull(channel, "A default is for some channel");
        return DeliveryMode.IMMEDIATE;
    }

    /**
     * What actually happens for one (type, channel), given whatever is stored.
     *
     * <p>Four rules, applied in this order, and each of them is somewhere a stored value
     * must not be taken at face value:
     *
     * <ol>
     *   <li><strong>A channel the type does not have is {@link DeliveryMode#OFF}.</strong>
     *       §4.10's crosses are not "off by default" — they are columns the row does not
     *       have, and no instruction turns them on. The fan-out does not even ask, so
     *       this is a backstop for any other caller.
     *   <li><strong>A mandatory category is {@link DeliveryMode#IMMEDIATE}.</strong>
     *       {@code NotificationCategory.SECURITY} explains why: the person who would want
     *       the alert silenced is the one who stole the account. {@code NotificationPreferences}
     *       refuses to store the instruction, so this only fires for a value that arrived
     *       by some other route — and it still does not silence the alert.
     *   <li><strong>No stored value is the default.</strong> {@link #defaultFor}.
     *   <li><strong>{@link DeliveryMode#DIGEST} on a channel that cannot digest is
     *       {@link DeliveryMode#IMMEDIATE}.</strong> An in-app inbox is already a list;
     *       see {@code NotificationChannel.isDigestible}. Also refused by the schema, and
     *       clamped rather than treated as off, because the person asked to be told and
     *       the disagreement is only about how.
     * </ol>
     *
     * @param stored what the recipient asked for, or null when they have never said
     */
    public static DeliveryMode resolve(NotificationType type, NotificationChannel channel, DeliveryMode stored) {
        Objects.requireNonNull(type, "A resolution is about some kind of notification");
        Objects.requireNonNull(channel, "A resolution is about some channel");

        if (!type.supports(channel)) {
            return DeliveryMode.OFF;
        }
        if (type.category().isMandatory()) {
            return DeliveryMode.IMMEDIATE;
        }
        if (stored == null) {
            return defaultFor(type, channel);
        }
        if (stored == DeliveryMode.DIGEST && !channel.isDigestible()) {
            return DeliveryMode.IMMEDIATE;
        }
        return stored;
    }
}
