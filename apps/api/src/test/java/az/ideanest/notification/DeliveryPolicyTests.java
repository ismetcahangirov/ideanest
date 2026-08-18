package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.DeliveryPolicy;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to somebody who has never opened the settings page, and the rules that
 * hold whatever they did there.
 *
 * <p>No database and no context, because {@code DeliveryPolicy} decides nothing from
 * configuration and reads nothing — the class comment says that is deliberate, and a
 * suite that started a container to check a pure function would make it untrue in
 * practice.
 *
 * <p><strong>The absent row is the case that matters.</strong> There is no seed in V26
 * and no row written at registration, so for almost every account and almost every
 * (category, channel) the stored value is null. If that resolved to {@code OFF} the
 * platform would tell nobody anything, and every other test in this module would still
 * pass, because they all write a preference or assert on rows that a different default
 * would simply not produce.
 */
class DeliveryPolicyTests {

    @Test
    @DisplayName("somebody who has never expressed a preference gets every channel their notifications have")
    void theAbsentRowIsImmediate() {
        for (NotificationType type : NotificationType.values()) {
            for (NotificationChannel channel : type.channels()) {
                assertThat(DeliveryPolicy.resolve(type, channel, null))
                        .as("%s on %s, with nothing stored", type, channel)
                        .isEqualTo(DeliveryMode.IMMEDIATE);
            }
        }
    }

    /**
     * §4.10's crosses are not "off by default" — they are columns the row does not have,
     * and no stored instruction turns one on.
     */
    @Test
    @DisplayName("a channel a notification does not have stays off, whatever is stored")
    void aChannelTheTypeDoesNotHaveIsOff() {
        for (NotificationType type : NotificationType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                if (type.supports(channel)) {
                    continue;
                }
                for (DeliveryMode stored : DeliveryMode.values()) {
                    assertThat(DeliveryPolicy.resolve(type, channel, stored))
                            .as("%s does not have %s, and %s does not add it", type, channel, stored)
                            .isEqualTo(DeliveryMode.OFF);
                }
            }
        }
    }

    /**
     * The person who would want the alert silenced is the one who stole the account.
     *
     * <p>{@code NotificationPreferences} refuses to store the instruction, so this only
     * fires for a value that arrived some other way — which is exactly why it is checked
     * here rather than assumed to be unreachable.
     */
    @Test
    @DisplayName("a mandatory category is immediate even when something stored says otherwise")
    void aMandatoryCategoryCannotBeSilenced() {
        NotificationType security = NotificationType.NEW_DEVICE_SIGN_IN;
        assertThat(security.category().isMandatory()).isTrue();

        for (NotificationChannel channel : security.channels()) {
            assertThat(DeliveryPolicy.resolve(security, channel, DeliveryMode.OFF))
                    .as("%s on %s, stored OFF", security, channel)
                    .isEqualTo(DeliveryMode.IMMEDIATE);
        }
    }

    /**
     * Clamped rather than treated as off: the person asked to be told, and the
     * disagreement is only about how.
     */
    @Test
    @DisplayName("digest on a channel that cannot digest becomes immediate, not off")
    void digestOnTheInboxIsClamped() {
        NotificationType type = NotificationType.PLEDGE_CONFIRMED;
        assertThat(NotificationChannel.IN_APP.isDigestible()).isFalse();
        assertThat(type.supports(NotificationChannel.IN_APP)).isTrue();

        assertThat(DeliveryPolicy.resolve(type, NotificationChannel.IN_APP, DeliveryMode.DIGEST))
                .isEqualTo(DeliveryMode.IMMEDIATE);
    }

    @Test
    @DisplayName("an expressed preference on a channel that can carry it is honoured")
    void anExpressedPreferenceIsHonoured() {
        NotificationType type = NotificationType.PLEDGE_CONFIRMED;
        assertThat(NotificationChannel.EMAIL.isDigestible()).isTrue();

        assertThat(DeliveryPolicy.resolve(type, NotificationChannel.EMAIL, DeliveryMode.OFF))
                .as("a non-mandatory category may be switched off entirely")
                .isEqualTo(DeliveryMode.OFF);
        assertThat(DeliveryPolicy.resolve(type, NotificationChannel.EMAIL, DeliveryMode.DIGEST))
                .isEqualTo(DeliveryMode.DIGEST);
    }
}
