package az.ideanest;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.observability.PlatformMetrics;
import az.ideanest.shared.observability.ProviderStatusSource;
import az.ideanest.shared.observability.QueueDepthSource;
import az.ideanest.shared.observability.ReconciliationStatusSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §8.4's three alerting conditions, as meters — issue #138.
 *
 * <p>A plain unit test against a {@link SimpleMeterRegistry}: what is worth checking is which
 * series exist, what they are tagged with and what they read, and none of that needs a Spring
 * context or a scrape endpoint.
 *
 * <p>WHAT THESE COVER:
 *
 * <ul>
 *   <li><strong>every gauge is re-asked on scrape.</strong> A gauge that captured a value at
 *       start-up would publish whether the provider was up when the application booted, for
 *       ever — which is the one failure mode that makes a monitoring system worse than none.
 *   <li><strong>an unconfigured provider publishes nothing at all.</strong> §9.3's choice is
 *       #60 and is unanswered, so a gauge reading zero would page somebody nightly about a
 *       provider the platform has never had.
 *   <li><strong>a reconciliation that has never run publishes no age.</strong> Not an age of
 *       fifty-six years, which would page during every deploy; `alerts.yml` reads the absence
 *       with `absent()`.
 *   <li><strong>a queue publishes both counts.</strong> Summing them would let a large backlog
 *       hide one message that will never be sent.
 * </ul>
 */
class MetricsTests {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("publishes both counts for every queue, tagged by which queue")
    void queuesPublishWaitingAndDead() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformMetrics(registry, List.of(queue("Outbox", 12, 1)), List.of(), List.of(), CLOCK);

        assertThat(gauge(registry, "ideanest.queue.waiting", "queue", "Outbox")).isEqualTo(12d);
        assertThat(gauge(registry, "ideanest.queue.dead", "queue", "Outbox")).isEqualTo(1d);
    }

    @Test
    @DisplayName("reads a queue again on every scrape rather than at start-up")
    void queueGaugesAreLive() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableQueue outbox = new MutableQueue();
        new PlatformMetrics(registry, List.of(outbox), List.of(), List.of(), CLOCK);

        assertThat(gauge(registry, "ideanest.queue.waiting", "queue", "Outbox")).isEqualTo(0d);
        outbox.waiting = 40;
        assertThat(gauge(registry, "ideanest.queue.waiting", "queue", "Outbox")).isEqualTo(40d);
    }

    @Test
    @DisplayName("publishes a provider's availability as one and zero")
    void providersPublishAvailability() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableProvider payriff = new MutableProvider(true);
        new PlatformMetrics(registry, List.of(), List.of(payriff), List.of(), CLOCK);

        assertThat(gauge(registry, "ideanest.provider.available", "provider", "PAYRIFF")).isEqualTo(1d);

        // The value the gauge exists to publish is exactly the thing that changes between
        // scrapes, so it must be re-asked rather than captured.
        payriff.available = false;
        assertThat(gauge(registry, "ideanest.provider.available", "provider", "PAYRIFF")).isEqualTo(0d);
    }

    /**
     * The platform has no provider today and will not until #60 is answered. A gauge reading
     * zero would page somebody nightly about a provider that has never existed; `alerts.yml`
     * fires on `== 0`, which an absent series cannot satisfy.
     */
    @Test
    @DisplayName("publishes nothing for a provider that is not configured")
    void anUnconfiguredProviderHasNoSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformMetrics(registry, List.of(), List.of(unconfiguredProvider()), List.of(), CLOCK);

        assertThat(registry.find("ideanest.provider.available").gauges()).isEmpty();
    }

    @Test
    @DisplayName("publishes the reconciliation's findings and how long ago it ran")
    void reconciliationPublishesBothFacts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformMetrics(
                registry, List.of(), List.of(), List.of(reconciliation(2, NOW.minusSeconds(3600))), CLOCK);

        assertThat(gauge(registry, "ideanest.ledger.reconciliation.findings")).isEqualTo(2d);
        assertThat(gauge(registry, "ideanest.ledger.reconciliation.age.seconds")).isEqualTo(3600d);
    }

    /**
     * A fresh replica has never reconciled, and must not report a perfectly recent
     * reconciliation it did not perform. Prometheus drops a NaN sample, so the series is
     * simply absent — which is what `absent()` in the alert rule reads.
     */
    @Test
    @DisplayName("publishes no age at all before the first pass")
    void anUnrunReconciliationHasNoAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformMetrics(registry, List.of(), List.of(), List.of(reconciliation(0, null)), CLOCK);

        assertThat(gauge(registry, "ideanest.ledger.reconciliation.findings")).isEqualTo(0d);
        assertThat(gauge(registry, "ideanest.ledger.reconciliation.age.seconds")).isNaN();
    }

    /* ---------------------------------------------------------------------- */

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge found = registry.find(name).gauge();
        assertThat(found).as(name).isNotNull();
        return found.value();
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String tag, String value) {
        Gauge found = registry.find(name).tag(tag, value).gauge();
        assertThat(found).as("%s{%s=%s}", name, tag, value).isNotNull();
        return found.value();
    }

    private static QueueDepthSource queue(String name, long waiting, long dead) {
        return new QueueDepthSource() {
            @Override
            public String queueName() {
                return name;
            }

            @Override
            public long waiting() {
                return waiting;
            }

            @Override
            public long dead() {
                return dead;
            }
        };
    }

    private static final class MutableQueue implements QueueDepthSource {
        private long waiting;

        @Override
        public String queueName() {
            return "Outbox";
        }

        @Override
        public long waiting() {
            return waiting;
        }

        @Override
        public long dead() {
            return 0;
        }
    }

    private static final class MutableProvider implements ProviderStatusSource {
        private boolean available;

        private MutableProvider(boolean available) {
            this.available = available;
        }

        @Override
        public List<ProviderStatus> providerStatuses() {
            return List.of(new ProviderStatus("payment", "PAYRIFF", true, available, null));
        }
    }

    private static ProviderStatusSource unconfiguredProvider() {
        return () -> List.of(new ProviderStatusSource.ProviderStatus("payment", "EPOINT", false, false, null));
    }

    private static ReconciliationStatusSource reconciliation(int findings, Instant lastRun) {
        return new ReconciliationStatusSource() {
            @Override
            public int findings() {
                return findings;
            }

            @Override
            public Instant lastRunAt() {
                return lastRun;
            }
        };
    }
}
