package az.ideanest.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often notifications are sent, how hard the platform tries, and how much of an
 * inbox one request may read.
 *
 * <p>Everything is defaulted in code rather than in {@code application.yml}, for the
 * reason {@code AnalyticsProperties} and {@code AbuseProperties} both give: a
 * deployment that configures none of this has to start, and the value it starts with
 * has to be the one somebody argued for rather than a zero left behind by binding.
 *
 * @param delivery the outbound queue — the second half of §12.2's diagram
 * @param inbox §10.2's {@code GET /v1/me/notifications}
 * @param rateLimit §17.3's shape applied to the one write this module exposes
 */
@ConfigurationProperties(prefix = "ideanest.notification")
public record NotificationProperties(Delivery delivery, Inbox inbox, RateLimit rateLimit) {

    public NotificationProperties {
        // A nested record binds to null when its whole block is absent, and a null here
        // would be a NullPointerException on the first notification rather than a
        // configuration error at start-up.
        delivery = delivery == null ? Delivery.defaults() : delivery;
        inbox = inbox == null ? Inbox.defaults() : inbox;
        rateLimit = rateLimit == null ? RateLimit.defaults() : rateLimit;
    }

    /**
     * The sender's own retry policy, which is deliberately the outbox's.
     *
     * <p>Same names, same meanings, same defaults. An operator looking at a stuck
     * notification at three in the morning should be reading the vocabulary they
     * already know from {@code outbox_events}, not a second one that happens to differ
     * in the third decimal place.
     *
     * @param sendSchedule how often the sender looks for work. Every second, like the
     *     relay and for the relay's reason: this is the second hop of the same path a
     *     person is watching — a pledge is confirmed, and the confirmation should be in
     *     their inbox before they have finished reading the page. A property rather than
     *     a constant so that the test profile can set it to {@code -}, Spring's own
     *     value for "do not schedule this": a sender firing in the background of a test
     *     suite sends the very rows a test is about to assert are pending
     * @param batchSize a bound on one pass, not a target. A backlog built up while a
     *     transport was down must not be one pass that overlaps its own next tick
     * @param maxAttempts sends attempted before the notification is a dead letter,
     *     including the first. A message refused the same way eight times is waiting for
     *     a person and not for the network
     * @param retryBackoff the delay after the first failure, doubled per attempt
     * @param maxBackoff the ceiling the doubling stops at
     */
    public record Delivery(
            String sendSchedule, int batchSize, int maxAttempts, Duration retryBackoff, Duration maxBackoff) {

        private static final String DEFAULT_SCHEDULE = "* * * * * *";

        private static final int DEFAULT_BATCH_SIZE = 100;

        private static final int DEFAULT_MAX_ATTEMPTS = 8;

        private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);

        private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMinutes(10);

        /**
         * Beyond this the doubling has certainly passed any sane ceiling, and shifting
         * further would overflow. The cap is applied before the arithmetic, not after.
         */
        private static final int LARGEST_USEFUL_EXPONENT = 30;

        static Delivery defaults() {
            return new Delivery(
                    DEFAULT_SCHEDULE,
                    DEFAULT_BATCH_SIZE,
                    DEFAULT_MAX_ATTEMPTS,
                    DEFAULT_RETRY_BACKOFF,
                    DEFAULT_MAX_BACKOFF);
        }

        public Delivery {
            sendSchedule = sendSchedule == null || sendSchedule.isBlank() ? DEFAULT_SCHEDULE : sendSchedule;
            batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
            maxAttempts = maxAttempts == 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
            retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
            maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;

            if (batchSize < 1) {
                throw new IllegalArgumentException("A sending pass sends at least one notification");
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("A notification is attempted at least once");
            }
            if (!retryBackoff.isPositive()) {
                throw new IllegalArgumentException("A retry waits for a positive duration");
            }
            if (maxBackoff.compareTo(retryBackoff) < 0) {
                throw new IllegalArgumentException("The backoff ceiling is not below the first delay");
            }
        }

        /**
         * How long to wait after {@code attempt} sends have failed.
         *
         * <p>{@code OutboxProperties.backoffAfter}, deliberately identical: exponential
         * from the first delay, capped at the ceiling.
         *
         * @param attempt which send has just failed, counted from one
         */
        public Duration backoffAfter(int attempt) {
            if (attempt < 1) {
                throw new IllegalArgumentException(
                        "Attempts are counted from one; there is no delay before the first");
            }
            if (attempt > LARGEST_USEFUL_EXPONENT) {
                return maxBackoff;
            }
            Duration backoff = retryBackoff.multipliedBy(1L << (attempt - 1));
            return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
        }
    }

    /**
     * @param defaultPageSize how many notifications a request that did not say gets
     * @param maxPageSize the ceiling on what one may ask for. Not a courtesy: the inbox
     *     is read with a bearer token, so an unbounded {@code limit} is a way for one
     *     caller to ask the database for somebody's entire history in one statement
     */
    public record Inbox(int defaultPageSize, int maxPageSize) {

        private static final int DEFAULT_PAGE_SIZE = 20;

        private static final int DEFAULT_MAX_PAGE_SIZE = 100;

        static Inbox defaults() {
            return new Inbox(DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGE_SIZE);
        }

        public Inbox {
            defaultPageSize = defaultPageSize == 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
            maxPageSize = maxPageSize == 0 ? DEFAULT_MAX_PAGE_SIZE : maxPageSize;

            if (defaultPageSize < 1) {
                throw new IllegalArgumentException("A page holds at least one notification");
            }
            if (maxPageSize < defaultPageSize) {
                throw new IllegalArgumentException("The page ceiling is not below the default page");
            }
        }
    }

    /**
     * @param preferenceUpdatesPerUser how many times one account may rewrite its
     *     settings in a window. Per account rather than per address, like every limit on
     *     a request that carries a token: an attacker holding one is not constrained by
     *     where they come from. Generous — a settings page saving one switch at a time
     *     legitimately sends several in a row — and it is the ceiling that matters, not
     *     the number
     * @param window the period that budget is counted over
     */
    public record RateLimit(int preferenceUpdatesPerUser, Duration window) {

        private static final int DEFAULT_UPDATES_PER_USER = 60;

        private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

        static RateLimit defaults() {
            return new RateLimit(DEFAULT_UPDATES_PER_USER, DEFAULT_WINDOW);
        }

        public RateLimit {
            // Failing open on a rate limit is a configuration mistake with no symptom
            // until it is exploited, so an omitted budget is the default and never
            // "unlimited".
            preferenceUpdatesPerUser =
                    preferenceUpdatesPerUser == 0 ? DEFAULT_UPDATES_PER_USER : preferenceUpdatesPerUser;
            window = window == null ? DEFAULT_WINDOW : window;

            if (preferenceUpdatesPerUser < 1) {
                throw new IllegalArgumentException("A caller may change at least one preference a window");
            }
            if (!window.isPositive()) {
                throw new IllegalArgumentException("A rate-limit window is a positive duration");
            }
        }
    }
}
