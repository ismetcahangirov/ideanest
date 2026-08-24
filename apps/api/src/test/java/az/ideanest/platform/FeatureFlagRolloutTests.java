package az.ideanest.platform;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.platform.domain.FeatureFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a rollout decides who is in — issue #312.
 *
 * <p>V50's header argues that a percentage has to be a stable hash rather than a sampled
 * set, because a sample has to be recomputed when the percentage moves and every
 * recomputation takes the feature away from somebody who had it. These are the assertions
 * that make that claim checkable rather than a comment.
 *
 * <p>The kill-switch behaviour is here too, and it is the one somebody relies on during an
 * incident: "I turned it off and it is still on for some people" is the worst property a
 * switch can have.
 */
class FeatureFlagRolloutTests {

    private static FeatureFlag flag(boolean enabled, int rollout, List<UUID> explicit) {
        return new FeatureFlag(
                "checkout-v2", "The rebuilt checkout", enabled, (short) rollout, explicit, UUID.randomUUID());
    }

    private static List<UUID> accounts(int count) {
        List<UUID> generated = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Deterministic rather than random, so a failure is reproducible: a rollout test
            // that fails one run in fifty is a test people delete.
            generated.add(UUID.nameUUIDFromBytes(("account-" + i).getBytes()));
        }
        return generated;
    }

    @Test
    @DisplayName("off is off for everybody, including the accounts named on the flag")
    void disabledOverridesEverything() {
        UUID insider = UUID.randomUUID();
        FeatureFlag off = flag(false, 100, List.of(insider));

        // The kill switch. An explicit list is an exception to the percentage, never to the
        // flag being disabled.
        assertThat(off.isOnFor(insider)).isFalse();
        assertThat(off.isOnFor(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("a named account is in whatever the percentage says")
    void explicitAccountsBypassThePercentage() {
        UUID insider = UUID.randomUUID();
        FeatureFlag zero = flag(true, 0, List.of(insider));

        assertThat(zero.isOnFor(insider)).isTrue();
        assertThat(zero.isOnFor(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("widening a rollout only ever adds people")
    void rolloutsAreMonotonic() {
        // The property the whole hashing decision exists for. Anybody inside ten percent must
        // still be inside twenty-five, fifty and a hundred -- otherwise somebody loses a
        // feature because the rollout went up, which is the defect a sampled set produces.
        List<UUID> population = accounts(500);

        List<UUID> previous = new ArrayList<>();
        for (int percentage : new int[] {0, 10, 25, 50, 75, 100}) {
            FeatureFlag current = flag(true, percentage, List.of());

            List<UUID> inside = population.stream().filter(current::isOnFor).toList();

            assertThat(inside)
                    .withFailMessage("widening to %d%% dropped somebody who was already in", percentage)
                    .containsAll(previous);

            previous = inside;
        }
    }

    @Test
    @DisplayName("the same account gets the same answer every time")
    void evaluationIsStable() {
        UUID account = UUID.nameUUIDFromBytes("stable".getBytes());

        FeatureFlag first = flag(true, 50, List.of());
        FeatureFlag second = flag(true, 50, List.of());

        // Two instances of the same flag must agree. They would not if the hash were
        // Objects.hash, whose result is unspecified across releases -- a rollout that
        // reshuffled on a JDK upgrade is exactly the defect isOnFor is written to avoid.
        assertThat(first.isOnFor(account)).isEqualTo(second.isOnFor(account));
        assertThat(first.isOnFor(account)).isEqualTo(first.isOnFor(account));
    }

    @Test
    @DisplayName("a rollout lands roughly where it says it does")
    void rolloutIsApproximatelyTheStatedShare() {
        List<UUID> population = accounts(2000);
        FeatureFlag half = flag(true, 50, List.of());

        long inside = population.stream().filter(half::isOnFor).count();

        // A hash is not a quota, so this is a band rather than an equality. Wide enough that
        // it will not fail on a different population, narrow enough to catch a bucketing
        // mistake -- a modulo that lost its floorMod, say, which would put every negative
        // hash outside every rollout.
        assertThat(inside).isBetween(800L, 1200L);
    }

    @Test
    @DisplayName("a signed-out visitor sees a partial rollout only when it has reached everybody")
    void anonymousVisitorsAreNotSampled() {
        // There is no stable identity to hash, and inventing one from the request would make
        // the flag flicker between page loads.
        assertThat(flag(true, 50, List.of()).isOnFor(null)).isFalse();
        assertThat(flag(true, 99, List.of()).isOnFor(null)).isFalse();
        assertThat(flag(true, 100, List.of()).isOnFor(null)).isTrue();
    }

    @Test
    @DisplayName("nought percent is nobody and a hundred is everybody")
    void theEndsAreAbsolute() {
        List<UUID> population = accounts(200);

        assertThat(population.stream().filter(flag(true, 0, List.of())::isOnFor)).isEmpty();
        assertThat(population.stream().filter(flag(true, 100, List.of())::isOnFor))
                .hasSize(population.size());
    }
}
