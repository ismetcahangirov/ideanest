package az.ideanest.moderation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trust-and-safety settings.
 *
 * <p>Every value has a default that a deployment configuring nothing still gets, for
 * {@code ProjectProperties}' reason: binding leaves an omitted property at its zero
 * value, and a rate limit of zero is an endpoint nobody can call while a page size
 * of zero is a queue that always looks empty. Both are configuration mistakes with
 * no symptom until somebody is trying to report a fraud.
 *
 * @param reports how much reporting one caller may do
 * @param queue how much of the queue one request may read
 */
@ConfigurationProperties(prefix = "ideanest.moderation")
public record ModerationProperties(Reports reports, Queue queue) {

    public ModerationProperties {
        reports = reports == null ? Reports.defaults() : reports;
        queue = queue == null ? Queue.defaults() : queue;
    }

    /** §17.3's shape applied to an endpoint that is abusable by design. */
    public static ModerationProperties defaults() {
        return new ModerationProperties(Reports.defaults(), Queue.defaults());
    }

    /**
     * The two budgets a report submission spends.
     *
     * <p><strong>Reporting is abusable in a way most writes are not.</strong> A
     * report is an accusation that costs a moderator's attention, and the cheapest
     * attack on a trust-and-safety queue is not to break it but to fill it — a
     * thousand reports about a competitor's campaign buries the fourteen about a
     * fraud. So the endpoint is limited twice, exactly as registration and launch
     * reminders are.
     *
     * @param perReporter how many reports one account may make per window. The
     *     tighter of the two and the one that matters: a report requires a signed-in
     *     account, so this is the budget an attacker actually has to spend
     * @param perClient how many one source address may make per window, counted
     *     separately, because the per-account limit alone does not bound somebody
     *     with fifty accounts. <strong>Deliberately much looser</strong> than the
     *     per-account number rather than a small multiple of it: registration is
     *     already limited per address, so acquiring the accounts is bounded upstream,
     *     and a tight per-address limit here would instead refuse the shared office,
     *     university or mobile-carrier NAT that a real reporter is sitting behind
     * @param window the period both are measured over. Fifteen minutes, matching
     *     {@code ideanest.project.reminders.window}, because these are the same kind
     *     of limit protecting against the same kind of script
     */
    public record Reports(int perReporter, int perClient, Duration window) {

        private static final int DEFAULT_PER_REPORTER = 20;

        /** See the record comment for why this is not a small multiple of the above. */
        private static final int DEFAULT_PER_CLIENT = 200;

        private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

        static Reports defaults() {
            return new Reports(DEFAULT_PER_REPORTER, DEFAULT_PER_CLIENT, DEFAULT_WINDOW);
        }

        public Reports {
            perReporter = perReporter == 0 ? DEFAULT_PER_REPORTER : perReporter;
            perClient = perClient == 0 ? DEFAULT_PER_CLIENT : perClient;
            window = window == null ? DEFAULT_WINDOW : window;

            if (perReporter < 1 || perClient < 1) {
                // A limit of zero is the reporting feature switched off, which is
                // not a configuration of it. An operator sees this at start-up
                // rather than as every report being refused.
                throw new IllegalArgumentException("A report rate limit has to allow at least one attempt");
            }
            if (!window.isPositive()) {
                throw new IllegalArgumentException("A rate limit window has to be a length of time");
            }
        }
    }

    /**
     * How much of the queue one request may read.
     *
     * @param defaultPageSize what a request that names no size gets
     * @param maxPageSize the ceiling. A bound rather than a preference: the queue is
     *     read by an authenticated member of staff, so this is not an abuse control —
     *     it is what stops one request from loading every report ever made into one
     *     response and one screen
     */
    public record Queue(int defaultPageSize, int maxPageSize) {

        private static final int DEFAULT_PAGE_SIZE = 50;

        private static final int DEFAULT_MAX_PAGE_SIZE = 200;

        static Queue defaults() {
            return new Queue(DEFAULT_PAGE_SIZE, DEFAULT_MAX_PAGE_SIZE);
        }

        public Queue {
            defaultPageSize = defaultPageSize == 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
            maxPageSize = maxPageSize == 0 ? DEFAULT_MAX_PAGE_SIZE : maxPageSize;

            if (defaultPageSize < 1 || maxPageSize < 1) {
                throw new IllegalArgumentException("A queue page holds at least one report");
            }
            if (defaultPageSize > maxPageSize) {
                // Otherwise the default is refused by the ceiling and every request
                // that names no size fails, which is a start-up problem wearing a
                // runtime costume.
                throw new IllegalArgumentException("The default queue page cannot exceed the maximum");
            }
        }
    }
}
