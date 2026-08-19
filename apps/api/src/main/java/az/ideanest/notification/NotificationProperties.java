package az.ideanest.notification;

import az.ideanest.notification.domain.DigestWindow;
import java.time.Duration;
import java.time.ZoneId;
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
 * @param digest §12.2's combining job: when a held notification stops being held
 * @param audience how much of a computed audience one event may fan out to
 * @param inbox §10.2's {@code GET /v1/me/notifications}
 * @param rateLimit §17.3's shape applied to the one write this module exposes
 */
@ConfigurationProperties(prefix = "ideanest.notification")
public record NotificationProperties(
        Delivery delivery, Digest digest, Audience audience, Inbox inbox, RateLimit rateLimit) {

    public NotificationProperties {
        // A nested record binds to null when its whole block is absent, and a null here
        // would be a NullPointerException on the first notification rather than a
        // configuration error at start-up.
        delivery = delivery == null ? Delivery.defaults() : delivery;
        digest = digest == null ? Digest.defaults() : digest;
        audience = audience == null ? Audience.defaults() : audience;
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
     * The combining job — §12.2's "notifications accumulate and a scheduled job combines them
     * into a single message", which until #244 was the one part of that sentence with nothing
     * behind it.
     *
     * @param schedule how often the platform <em>asks</em> whether a digest is due. Hourly,
     *     and that is not the cadence of the digest: {@code DigestWindow} sends everything
     *     held from before the most recently closed digest hour, so the cron decides how
     *     promptly a due digest goes out and never whether it goes out at all. A tick missed
     *     at the digest hour is caught by the next one. A property rather than a constant so
     *     that the test profile can set it to {@code -}, Spring's own value for "do not
     *     schedule this" — a combining job firing in the background of a suite sends the very
     *     rows a test is about to assert are held
     * @param zone whose clock {@link #atHour} is read on. {@code RollupWindow} makes the
     *     argument for a zone rather than UTC and every word of it applies: Baku is UTC+4, so
     *     a fixed UTC hour is a different local hour, and the entire point of choosing an hour
     *     is that it is a reasonable time of day for the person receiving the mail. A
     *     {@code ZoneId} rather than an offset, so that a zone which observes daylight saving
     *     keeps the local hour rather than drifting an hour twice a year
     * @param atHour the local hour a digest goes out at. Eight in the morning: late enough
     *     that a phone is not waking somebody, early enough that a campaign closing today is
     *     still something they can act on. A product decision, stated here and in
     *     {@code DigestWindow} rather than left in a cron expression nobody reads
     * @param batchSize how many (recipient, channel) groups one pass may combine. A bound on
     *     the pass, not a target — the same argument {@link Delivery#batchSize} makes: a
     *     backlog built up while the job was failing must not become one pass that overlaps
     *     its own next tick and holds a lease past its lease time
     * @param maxNotificationsPerMessage how many notifications may go into one digest.
     *     <strong>The remainder is not dropped and is not silently deferred</strong>: it stays
     *     {@code HELD}, remains due — its period has already closed — and the next pass sends
     *     it as a further message, which {@code DigestAssembly} logs. Two hundred is well past
     *     any digest a person reads and well short of a message a transport would refuse for
     *     size; the point of the bound is that one recipient cannot make one message unbounded
     */
    public record Digest(String schedule, ZoneId zone, int atHour, int batchSize, int maxNotificationsPerMessage) {

        /** Hourly, on the hour. See {@link #schedule} for why this is not the cadence. */
        private static final String DEFAULT_SCHEDULE = "0 0 * * * *";

        /** The platform's zone, the same one {@code AnalyticsAggregationProperties} defaults to. */
        private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Baku");

        private static final int DEFAULT_AT_HOUR = 8;

        private static final int DEFAULT_BATCH_SIZE = 100;

        private static final int DEFAULT_MAX_NOTIFICATIONS_PER_MESSAGE = 200;

        private static final int LATEST_HOUR = 23;

        static Digest defaults() {
            return new Digest(
                    DEFAULT_SCHEDULE,
                    DEFAULT_ZONE,
                    DEFAULT_AT_HOUR,
                    DEFAULT_BATCH_SIZE,
                    DEFAULT_MAX_NOTIFICATIONS_PER_MESSAGE);
        }

        public Digest {
            schedule = schedule == null || schedule.isBlank() ? DEFAULT_SCHEDULE : schedule;
            zone = zone == null ? DEFAULT_ZONE : zone;
            batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
            maxNotificationsPerMessage = maxNotificationsPerMessage == 0
                    ? DEFAULT_MAX_NOTIFICATIONS_PER_MESSAGE
                    : maxNotificationsPerMessage;

            // Not defaulted from zero, unlike every number above it: midnight is a legitimate
            // digest hour, so "absent" and "zero" cannot be told apart here and treating zero
            // as absent would make 00:00 unconfigurable. An absent block is handled by
            // Digest.defaults() instead, and a partial block that omits this one gets
            // midnight — which is a stated hour rather than a silent fallback.
            if (atHour < 0 || atHour > LATEST_HOUR) {
                throw new IllegalArgumentException(
                        "A digest goes out at an hour of the day, and " + atHour + " is not one");
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("A combining pass combines at least one digest");
            }
            if (maxNotificationsPerMessage < 1) {
                throw new IllegalArgumentException("A digest holds at least one notification");
            }
        }

        /** The decision {@code DigestWindow} makes, as the value the job asks. */
        public DigestWindow window() {
            return DigestWindow.at(zone, atHour);
        }
    }

    /**
     * The bound on an audience the platform computes — §4.10's "a campaign's backers".
     *
     * @param maxRecipients how many people one event may fan out to.
     *     <strong>A limit on one transaction, and the reason there is one at all is that the
     *     fan-out runs inside the outbox dispatch.</strong> "Goal reached" on a campaign with
     *     twenty thousand backers is sixty thousand rows written by the transaction that also
     *     marks the event delivered, plus an {@code IN} list of twenty thousand identifiers to
     *     check they are accounts — one transaction whose size is decided by how well a campaign
     *     did, holding locks for as long as it takes.
     *     <p><strong>Exceeding it is logged at {@code ERROR} and never silent.</strong>
     *     {@code NotificationEventListener} asks the audience port for one more than this and
     *     compares, so truncation is a fact it knows rather than infers, and it says which
     *     campaign and how many people were not told. A fan-out chunked across several
     *     transactions is what removes the bound rather than raising it, and it is not this
     *     change.
     *     <p>Five thousand: comfortably above every campaign the platform has, and small enough
     *     that the transaction above is one an operator would not notice. It is a ceiling rather
     *     than a target
     */
    public record Audience(int maxRecipients) {

        private static final int DEFAULT_MAX_RECIPIENTS = 5_000;

        static Audience defaults() {
            return new Audience(DEFAULT_MAX_RECIPIENTS);
        }

        public Audience {
            maxRecipients = maxRecipients == 0 ? DEFAULT_MAX_RECIPIENTS : maxRecipients;

            if (maxRecipients < 1) {
                throw new IllegalArgumentException("An event tells at least one person");
            }
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
