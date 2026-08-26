package az.ideanest.payment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * §9.6's collection attempts, counted by what happened — §8.4's second alert, issue #138.
 *
 * <h2>A counter per outcome, and the alert is the ratio</h2>
 *
 * "Collection failure rate" is not a number this service can publish, because a rate needs a
 * window and a window is a decision about how long a bad minute has to last before it is a bad
 * afternoon. What is published is the raw counts; `alerts.yml` divides them over five minutes,
 * which is where that decision belongs — changing it is editing a rule rather than deploying
 * the service.
 *
 * <p><strong>Every outcome, not just the failures.</strong> A denominator is what makes a
 * failure count mean anything: ten declines out of ten thousand collections is a Tuesday, and
 * ten out of twelve is an incident. Counting only the failures would produce an alert that
 * fires hardest on the platform's busiest day.
 *
 * <h2>Registered eagerly, all of them</h2>
 *
 * Every counter is created at start-up rather than on first use. A Micrometer counter that has
 * never been incremented does not exist, so a series that only appears the first time a
 * collection fails is a series `rate()` cannot evaluate over the window that matters — and the
 * alert stays silent for precisely the first five minutes of the incident.
 *
 * <h2>Where it is called from, and where it is not</h2>
 *
 * {@code ChargeProcessorJob} and {@code ChargeRetryJob}, which are the two callers of
 * {@code CollectionRun.collectNext}. Not inside {@code CollectionRun} itself: that method runs
 * in the transaction that moves somebody's money, and a metric registry is not something that
 * belongs on that path — a counter that threw would roll back a charge that succeeded.
 */
@Component
public class CollectionMetrics {

    private final Map<CollectionOutcome, Counter> counters = new EnumMap<>(CollectionOutcome.class);

    public CollectionMetrics(MeterRegistry registry) {
        for (CollectionOutcome outcome : CollectionOutcome.values()) {
            counters.put(
                    outcome,
                    Counter.builder("ideanest.payment.collection.attempts")
                            .description("Collection attempts, by what came of them")
                            .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                            .register(registry));
        }
    }

    /** Records one attempt. Never throws: see the class comment on where this is called. */
    public void record(CollectionOutcome outcome) {
        Counter counter = counters.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }
}
