package az.ideanest.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduler's retry policy, as arithmetic rather than as a description.
 *
 * <p>Deliberately a plain unit test: none of this needs a database. The rule is the
 * same one {@code OutboxPropertiesTests} checks for the relay, and it is checked
 * again here rather than assumed, because the two records are configured separately
 * and a deployment can get one of them wrong.
 */
class JobPropertiesTests {

    @Test
    @DisplayName("an unconfigured deployment gets the policy, not a zeroed one")
    void omittedPropertiesFallBackToTheDefaults() {
        // Binding leaves an omitted property at its zero value. Without these
        // fallbacks a deployment that configures none of this would take a lease of
        // zero -- every job claimable by every replica at once, which is the failure
        // this package exists to prevent, arrived at by omission.
        JobProperties properties = new JobProperties(null, null, 0, null, null);

        assertThat(properties.holder()).isNotBlank();
        assertThat(properties.lockLease()).isPositive();
        assertThat(properties.maxAttempts()).isGreaterThan(1);
        assertThat(properties.retryBackoff()).isPositive();
        assertThat(properties.maxBackoff()).isGreaterThanOrEqualTo(properties.retryBackoff());
    }

    @Test
    @DisplayName("the delay doubles per consecutive failure and stops at the cap")
    void backoffGrowsAndThenHolds() {
        JobProperties properties = new JobProperties(
                "replica-a", Duration.ofMinutes(1), 20, Duration.ofSeconds(10), Duration.ofMinutes(5));

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(40));
        // Capped rather than doubling for ever. A job whose next attempt is in nine
        // hours has stopped running in every practical sense while still calling
        // itself retryable.
        assertThat(properties.backoffAfter(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(1000)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a policy that cannot run anything is refused at start-up")
    void impossiblePoliciesAreRefused() {
        // Fail on the way up rather than on the first tick. Negative rather than
        // zero, because zero is an omitted property and binding has to give that the
        // default instead of refusing to start.
        assertThatThrownBy(() -> new JobProperties(
                        "replica-a", Duration.ZERO, 8, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProperties(
                        "replica-a", Duration.ofMinutes(1), -1, Duration.ofSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProperties(
                        "replica-a", Duration.ofMinutes(1), 8, Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobProperties(
                        "replica-a", Duration.ofMinutes(1), 8, Duration.ofMinutes(5), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an attempt that has not happened yet has no delay to serve")
    void theFirstAttemptIsNumberOne() {
        JobProperties properties = new JobProperties(
                "replica-a", Duration.ofMinutes(1), 20, Duration.ofSeconds(10), Duration.ofMinutes(5));

        // Counted from one, because the column they are compared against counts
        // failures that have happened. An off-by-one here is either a retry nobody
        // waited for or a job that gives up one attempt early.
        assertThatThrownBy(() -> properties.backoffAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
