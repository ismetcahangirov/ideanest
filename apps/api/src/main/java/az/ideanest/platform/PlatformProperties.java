package az.ideanest.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The console's platform-wide surfaces — AD-12 and AD-16, issues #312 and #316.
 *
 * @param flags how long a flag evaluation may be stale
 * @param health what counts as a queue that is too deep
 */
@ConfigurationProperties(prefix = "ideanest.platform")
public record PlatformProperties(Flags flags, Health health) {

    public PlatformProperties {
        flags = flags == null ? Flags.defaults() : flags;
        health = health == null ? Health.defaults() : health;
    }

    /**
     * @param cacheTtl how long {@code FeatureFlags} holds the table before re-reading it.
     *     <p><strong>The number that decides whether a kill switch is one.</strong> An
     *     edit clears the cache on the instance that made it, so this window is only ever
     *     the delay between one instance and another — but during an incident that is
     *     exactly the delay somebody is watching. Ten seconds is short enough that nobody
     *     reaches for a restart and long enough that the table is not read on every page
     *     render on the platform.
     */
    public record Flags(Duration cacheTtl) {

        private static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(10);

        public static Flags defaults() {
            return new Flags(DEFAULT_CACHE_TTL);
        }

        public Flags {
            cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;

            if (cacheTtl.isNegative()) {
                throw new IllegalArgumentException("A flag cache cannot be held for a negative time");
            }
        }
    }

    /**
     * @param queueDepthWarning how many waiting rows make a queue worth looking at
     * @param queueDepthCritical how many make it worth waking somebody
     * @param lateJobAfter how far past its due time a job may be before it is late at all —
     *     issue #405. <strong>Being due is not being late.</strong> A scheduler picks a
     *     job up in the seconds after its next attempt falls due, so every healthy job on
     *     the platform spends part of its cycle a few seconds past due; grading those
     *     amber painted ten of nineteen rows as worth looking at, each of them reading
     *     "0 minutes late", beside the two that were genuinely days behind and were the
     *     same colour. A dashboard whose majority is amber for no stated reason is how a
     *     real one gets missed. One minute, so that the screen's own rounding and the
     *     severity agree: nothing can be shown as late by zero minutes again
     * @param staleJobAfter how far past its due time a job may be before the screen calls
     *     it late. <strong>Not the same as failed:</strong> a job whose next attempt is
     *     three minutes overdue is a scheduler under load, and one that is three hours
     *     overdue is a scheduler that is not running. Only the second is worth a page,
     *     and a screen that could not tell them apart would be one people stop reading
     */
    public record Health(
            int queueDepthWarning, int queueDepthCritical, Duration lateJobAfter, Duration staleJobAfter) {

        private static final int DEFAULT_WARNING = 100;

        private static final int DEFAULT_CRITICAL = 1000;

        /** One minute: the smallest lateness the screen can render as a number above zero. */
        private static final Duration DEFAULT_LATE_AFTER = Duration.ofMinutes(1);

        private static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);

        public static Health defaults() {
            return new Health(DEFAULT_WARNING, DEFAULT_CRITICAL, DEFAULT_LATE_AFTER, DEFAULT_STALE_AFTER);
        }

        public Health {
            queueDepthWarning = queueDepthWarning == 0 ? DEFAULT_WARNING : queueDepthWarning;
            queueDepthCritical = queueDepthCritical == 0 ? DEFAULT_CRITICAL : queueDepthCritical;
            lateJobAfter = lateJobAfter == null ? DEFAULT_LATE_AFTER : lateJobAfter;
            staleJobAfter = staleJobAfter == null ? DEFAULT_STALE_AFTER : staleJobAfter;

            if (queueDepthWarning < 1 || queueDepthCritical <= queueDepthWarning) {
                // A critical threshold at or below the warning would make the screen show
                // red before amber, which is the shape of a dashboard people learn to
                // ignore.
                throw new IllegalArgumentException("Queue thresholds go warning, then critical");
            }
            if (lateJobAfter.isNegative() || !lateJobAfter.minus(staleJobAfter).isNegative()) {
                // The same rule one layer up: amber has to come before red, or the screen
                // never shows amber at all and the distinction the two thresholds exist to
                // draw is not drawn.
                throw new IllegalArgumentException("Job thresholds go late, then stale");
            }
        }
    }
}
