package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.shared.outbox.OutboxProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retry policy, as arithmetic rather than as a description.
 *
 * <p>Deliberately a plain unit test: none of this needs a database, and the backoff is
 * the one part of the relay that is a calculation. A cap that does not hold is a delay
 * that doubles until it is measured in days, and an event nobody notices has stopped
 * moving; a first delay of zero is a tight loop against a transport that is already
 * struggling.
 */
class OutboxPropertiesTests {

    @Test
    @DisplayName("an unconfigured deployment gets the policy, not a zeroed one")
    void omittedPropertiesFallBackToTheDefaults() {
        // Binding leaves an omitted property at its zero value. Without these
        // fallbacks a deployment that configures none of this would retry zero times —
        // every transient failure a dead letter — and poll on a blank cron.
        OutboxProperties properties = new OutboxProperties(null, 0, 0, null, null);

        assertThat(properties.pollSchedule()).isNotBlank();
        assertThat(properties.batchSize()).isPositive();
        assertThat(properties.maxAttempts()).isGreaterThan(1);
        assertThat(properties.retryBackoff()).isPositive();
        assertThat(properties.maxBackoff()).isGreaterThanOrEqualTo(properties.retryBackoff());
    }

    @Test
    @DisplayName("the delay doubles per attempt and stops at the cap")
    void backoffGrowsAndThenHolds() {
        OutboxProperties properties =
                new OutboxProperties("-", 10, 20, Duration.ofSeconds(10), Duration.ofMinutes(5));

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(40));
        // Capped rather than doubling for ever. Ten seconds doubled twenty times is
        // over a hundred days, which is not a retry.
        assertThat(properties.backoffAfter(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(1000)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a policy that cannot deliver anything is refused at start-up")
    void impossiblePoliciesAreRefused() {
        // Fail on the way up rather than on the first event: every one of these is a
        // configuration that silently loses messages.
        // Negative rather than zero, because zero is an omitted property and binding
        // has to give that the default instead of refusing to start.
        assertThatThrownBy(() -> new OutboxProperties("-", -1, 8, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties("-", 10, -1, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties("-", 10, 20, Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxProperties("-", 10, 20, Duration.ofMinutes(5), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an attempt that has not happened yet has no delay to serve")
    void theFirstAttemptIsNumberOne() {
        OutboxProperties properties =
                new OutboxProperties("-", 10, 20, Duration.ofSeconds(10), Duration.ofMinutes(5));

        // Attempts are counted from one, because the column they are compared against
        // counts deliveries that have been made. An off-by-one here is either a retry
        // nobody waited for or a dead letter one attempt early.
        assertThatThrownBy(() -> properties.backoffAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
