package az.ideanest.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The three things §8.4 asks to be alerted on, as meters — issue #138.
 *
 * <h2>Bound from the sources that already exist</h2>
 *
 * §4.11's health screen (#316) already asks every module the questions this needs answered:
 * {@link QueueDepthSource} for what is waiting, {@link ProviderStatusSource} for whether a
 * provider is reachable. #70 added {@link ReconciliationStatusSource} for whether the books
 * balance. Reading those interfaces rather than the tables underneath them is what keeps this
 * class in {@code shared} — it names three interfaces and no module.
 *
 * <p>The screen and the metrics are deliberately the same numbers from the same place. A
 * dashboard that disagreed with the alert would be a dashboard somebody checks after being
 * paged and then distrusts.
 *
 * <h2>GAUGES AND NOT COUNTERS, AND WHY THAT IS NOT A SHORTCUT</h2>
 *
 * Every value here is a level rather than an event: how many are queued now, whether the
 * provider is up now, how many findings the last pass had. A counter would be the wrong
 * instrument — it would only ever go up, and "how many times has the ledger been unbalanced
 * since this process started" is not a question anybody has.
 *
 * <p>Each gauge holds a strong reference to its source, which is the {@code MeterRegistry}
 * default this deliberately does not fight: these sources are singletons for the life of the
 * application, and a weak reference would let a gauge quietly become {@code NaN} if a future
 * refactor made one of them a prototype.
 *
 * <h2>What is deliberately not measured here</h2>
 *
 * <strong>Anything with a person in it.</strong> §17.4 keeps personal data out of the log
 * stream and a metric label is worse than a log line: it is retained longer, indexed, and
 * shipped to whatever scrapes it. No account identifier, no email, no campaign — the only
 * labels below are a queue's name, a provider's name and a currency, and none of those is
 * about anybody.
 *
 * <strong>Latency and throughput.</strong> Spring Boot's own binders already publish HTTP,
 * JDBC, JVM and Hikari metrics; adding a second measurement of a request would be two answers
 * to one question. What is here is what nothing else could know.
 */
@Component
public class PlatformMetrics {

    /** Prefix for everything this platform publishes, so a scraper can select on it. */
    private static final String PREFIX = "ideanest.";

    public PlatformMetrics(
            MeterRegistry registry,
            List<QueueDepthSource> queues,
            List<ProviderStatusSource> providers,
            List<ReconciliationStatusSource> reconciliations,
            Clock clock) {

        bindQueues(registry, queues);
        bindProviders(registry, providers);
        bindReconciliation(registry, reconciliations, clock);
    }

    /**
     * Two gauges per queue, never one.
     *
     * <p>{@code QueueDepthSource} makes the argument and it is an alerting argument as much as
     * a screen one: a deep queue is a platform under load and a dead row is a platform that
     * has given up. Summing them would let a thousand-item backlog hide one message that will
     * never be sent, and the two deserve different thresholds and different urgencies.
     */
    private static void bindQueues(MeterRegistry registry, List<QueueDepthSource> queues) {
        for (QueueDepthSource queue : queues) {
            Gauge.builder(PREFIX + "queue.waiting", queue, QueueDepthSource::waiting)
                    .description("Rows waiting to be handled in one of the platform's queues")
                    .tag("queue", queue.queueName())
                    .strongReference(true)
                    .register(registry);

            Gauge.builder(PREFIX + "queue.dead", queue, QueueDepthSource::dead)
                    .description("Rows that ran out of attempts and will not be retried")
                    .tag("queue", queue.queueName())
                    .strongReference(true)
                    .register(registry);
        }
    }

    /**
     * Whether each provider is reachable, as one and zero.
     *
     * <p><strong>A provider that is not configured publishes nothing at all</strong>, rather
     * than publishing zero. §9.3's choice is #60 and is unanswered, so a deployment today has
     * no provider — and a gauge reading zero would page somebody nightly about a provider the
     * platform has never had. An absent series is the honest statement, and `alerts.yml` uses
     * `absent()` where it needs to say "there should be one and there is not".
     */
    private static void bindProviders(MeterRegistry registry, List<ProviderStatusSource> providers) {
        for (ProviderStatusSource source : providers) {
            for (ProviderStatusSource.ProviderStatus status : source.providerStatuses()) {
                if (!status.configured()) {
                    continue;
                }
                Gauge.builder(PREFIX + "provider.available", source, current -> available(current, status.name()))
                        .description("1 when the provider is answering, 0 when the breaker has opened")
                        .tag("kind", status.kind())
                        .tag("provider", status.name())
                        .strongReference(true)
                        .register(registry);
            }
        }
    }

    /**
     * Re-asked on every scrape rather than captured once.
     *
     * <p>The status list is a snapshot: it is built when the source is asked, and the value
     * this gauge exists to publish is exactly the thing that changes between scrapes. Reading
     * the captured boolean would publish whether the provider was up when the application
     * started, for ever.
     */
    private static double available(ProviderStatusSource source, String provider) {
        return source.providerStatuses().stream()
                .filter(status -> status.name().equals(provider))
                .findFirst()
                .map(status -> status.available() ? 1d : 0d)
                .orElse(0d);
    }

    /**
     * Two gauges: how wrong the books are, and how long since anybody looked.
     *
     * <p>The age is published only once a pass has run on this replica. See
     * {@link ReconciliationStatusSource#lastRunAt()} for why an absent series beats an age of
     * fifty-six years — and `alerts.yml` for the `absent()` rule that catches it anyway.
     */
    private static void bindReconciliation(
            MeterRegistry registry, List<ReconciliationStatusSource> sources, Clock clock) {

        for (ReconciliationStatusSource source : sources) {
            Gauge.builder(PREFIX + "ledger.reconciliation.findings", source, ReconciliationStatusSource::findings)
                    .description("Discrepancies found by the most recent ledger reconciliation")
                    .strongReference(true)
                    .register(registry);

            Gauge.builder(
                            PREFIX + "ledger.reconciliation.age.seconds",
                            source,
                            current -> ageSeconds(current, clock))
                    .description("Seconds since the last ledger reconciliation ran on this instance")
                    .baseUnit("seconds")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private static double ageSeconds(ReconciliationStatusSource source, Clock clock) {
        Instant lastRun = source.lastRunAt();
        // NaN rather than zero for a pass that has never run. Prometheus drops a NaN sample,
        // so the series is simply absent — which is what `absent()` in the alert rule reads,
        // and what stops a fresh replica reporting a perfectly recent reconciliation it never
        // performed.
        return lastRun == null ? Double.NaN : (clock.instant().toEpochMilli() - lastRun.toEpochMilli()) / 1000d;
    }
}
