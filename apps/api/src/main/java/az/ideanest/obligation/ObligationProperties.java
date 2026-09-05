package az.ideanest.obligation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §5.5's interval, the warning that comes before it, and when the sweep runs — issue #437.
 *
 * @param interval how long a creator has between updates. §5.5 says "at least monthly"; thirty
 *     days rather than a calendar month, because a calendar month makes the obligation shorter
 *     in February for no reason anybody could defend to a creator
 * @param reminderLead how long before the due date the creator is warned, and the same value the
 *     page uses to say "due soon". §437: "the obligation is monthly; the reminder is before the
 *     month is out. The point is compliance, not catching people"
 * @param schedule when the sweep fires, as a UTC cron expression, or {@code -} to register the
 *     job without scheduling it. Daily and deliberately not midnight, for
 *     {@code PaymentProperties.Reconciliation}'s reason
 * @param perPass how many obligations one pass may act on
 */
@ConfigurationProperties(prefix = "ideanest.obligation")
public record ObligationProperties(
        Duration interval, Duration reminderLead, String schedule, int perPass) {

    private static final Duration DEFAULT_INTERVAL = Duration.ofDays(30);
    private static final Duration DEFAULT_REMINDER_LEAD = Duration.ofDays(7);
    private static final String DEFAULT_SCHEDULE = "0 15 3 * * *";
    private static final int DEFAULT_PER_PASS = 200;

    public ObligationProperties {
        // A deployment that configures none of this still starts, for PaymentProperties' reason.
        interval = interval == null ? DEFAULT_INTERVAL : interval;
        reminderLead = reminderLead == null ? DEFAULT_REMINDER_LEAD : reminderLead;
        schedule = schedule == null || schedule.isBlank() ? DEFAULT_SCHEDULE : schedule;
        perPass = perPass <= 0 ? DEFAULT_PER_PASS : perPass;
        if (reminderLead.compareTo(interval) >= 0) {
            // A lead at least as long as the interval means every obligation is DUE_SOON from
            // the moment it opens, and the reminder fires the day a campaign closes. Refused at
            // start-up rather than producing a platform that nags every creator immediately.
            throw new IllegalArgumentException(
                    "ideanest.obligation.reminder-lead (%s) must be shorter than the interval (%s)"
                            .formatted(reminderLead, interval));
        }
    }
}
