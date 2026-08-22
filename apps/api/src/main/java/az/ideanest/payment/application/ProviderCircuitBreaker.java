package az.ideanest.payment.application;

import az.ideanest.payment.PaymentProperties;
import az.ideanest.payment.domain.ProviderName;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §9.3's warning, made operational: stop asking a provider that has stopped answering
 * (#64).
 *
 * <p>"If the primary is unavailable on the day a large campaign closes, the entire
 * business stops." The answer to that is a second provider, which is #61's interface
 * and an adapter nobody has written. <strong>This is not that answer</strong>, and it
 * is worth saying so plainly rather than letting the name imply otherwise. What it does
 * is bound the damage of an outage: a campaign with four thousand pledges would
 * otherwise spend every pass rediscovering the same failure a hundred times, producing
 * a hundred rows in {@code transactions}, a hundred lines in the log, and a hundred
 * requests at a provider that is already struggling.
 *
 * <h2>Only unavailability counts</h2>
 *
 * <p>A decline never opens the breaker. §9.6 puts collection failure at 5–15% of
 * pledges, so a breaker that counted declines would open on a perfectly healthy
 * campaign and stop collecting the other 85%. What counts is
 * {@code ProviderUnavailableException}: the platform could not ask, which is a
 * statement about the provider rather than about anybody's card.
 *
 * <h2>Per replica, in memory, and that is a real bound</h2>
 *
 * <p>There is no shared state here: each replica counts its own failures and opens its
 * own breaker. A fleet of three therefore makes up to three times the threshold before
 * every replica has stopped, and a replica that restarts starts counting again. Both
 * are accepted rather than solved, because the alternatives cost more than they save —
 * a row in PostgreSQL means a write on every provider call, and the collection is
 * already claimed one pledge at a time by a job that runs once across the fleet, so in
 * practice one replica is doing the charging and the divergence is theoretical.
 *
 * <p>The state is also deliberately not durable. A breaker that survived a restart
 * would mean a deploy during an outage came up already refusing to collect, with
 * nothing in the new process's logs to say why.
 *
 * <h2>Half-open is one call, not a state</h2>
 *
 * <p>When the cooldown elapses the breaker simply closes and the next pass tries. There
 * is no probe and no half-open counter, because the caller is a job that runs every
 * minute against a queue it can re-read: the "probe" is the next pledge, and if the
 * provider is still down the breaker opens again after the threshold. A half-open state
 * would be machinery for deciding when to try, in a component whose caller already has
 * a schedule.
 */
@Component
public class ProviderCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    private final PaymentProperties.CircuitBreaker properties;
    private final Clock clock;

    /**
     * Per provider, because §9.3 asks for two of them and one being down says nothing
     * about the other.
     */
    private final Map<ProviderName, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();

    private final Map<ProviderName, Instant> openUntil = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(PaymentProperties properties, Clock clock) {
        this.properties = properties.circuitBreaker();
        this.clock = clock;
    }

    /**
     * Whether the platform should ask this provider anything at all right now.
     *
     * <p>Asked once per charge rather than once per pass, so that a breaker which opens
     * part-way through a batch stops the rest of it.
     */
    public boolean isAvailable(ProviderName provider) {
        Instant until = openUntil.get(provider);
        if (until == null) {
            return true;
        }
        if (clock.instant().isBefore(until)) {
            return false;
        }
        // The cooldown has elapsed. Closing here rather than in a timer means the breaker
        // costs nothing while nobody is collecting, and it means the state is only ever
        // changed by a thread that is about to make a call.
        openUntil.remove(provider);
        consecutiveFailures.remove(provider);
        log.info("The circuit breaker for {} has closed; collection will be attempted again.", provider);
        return true;
    }

    /**
     * A call that reached the provider and came back, whatever it said.
     *
     * <p>Called on an approval <em>and</em> on a decline, which is the point: a decline
     * is evidence that the provider is up. Resetting on it is what stops a run of
     * declines from an ordinary campaign accumulating towards the threshold in between
     * two genuine outages.
     */
    public void recordAnswered(ProviderName provider) {
        consecutiveFailures.remove(provider);
    }

    /**
     * A call the platform could not complete.
     *
     * @return whether this failure opened the breaker, so that the caller can say so once
     *     rather than the breaker logging on every subsequent refusal
     */
    public boolean recordUnavailable(ProviderName provider) {
        int failures = consecutiveFailures
                .computeIfAbsent(provider, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (failures < properties.failureThreshold()) {
            return false;
        }
        Instant until = clock.instant().plus(properties.cooldown());
        // putIfAbsent, so that several threads crossing the threshold together do not each
        // extend the cooldown -- an outage would otherwise hold the breaker open for the
        // cooldown times the number of concurrent charges.
        boolean opened = openUntil.putIfAbsent(provider, until) == null;
        if (opened) {
            log.error(
                    "The circuit breaker for {} has opened after {} consecutive failures; "
                            + "collection stops until {}.",
                    provider,
                    failures,
                    until);
        }
        return opened;
    }

    /** Whether this provider's breaker is open, for a test and for the operator's read. */
    public boolean isOpen(ProviderName provider) {
        return !isAvailable(provider);
    }

    /**
     * Forgets everything about every provider.
     *
     * <p>For tests, which share a Spring context across classes and would otherwise
     * inherit a breaker another test opened. There is no operational caller: an operator
     * who wants the breaker closed waits out a cooldown measured in seconds.
     */
    public void reset() {
        consecutiveFailures.clear();
        openUntil.clear();
    }
}
