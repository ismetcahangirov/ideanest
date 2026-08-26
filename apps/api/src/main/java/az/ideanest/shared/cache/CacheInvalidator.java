package az.ideanest.shared.cache;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tells the web client that a page it has cached is no longer true — issue #127.
 *
 * <h2>A HINT, NOT A DELIVERY GUARANTEE, and everything here follows from that</h2>
 *
 * <p>Every cached read on the far side carries a sixty-second window of its own. So the worst
 * consequence of an invalidation that is dropped, refused or never attempted is a page that is
 * up to a minute stale — which is exactly what the platform did before this existed. That is
 * not a failure to be retried; it is the floor this feature is built on top of, and it is what
 * makes the three decisions below correct rather than lazy.
 *
 * <p><strong>It never throws.</strong> {@link CacheInvalidationListener} runs inside the outbox
 * relay's dispatch transaction, and {@code OutboxDispatcher}'s contract is explicit: a
 * {@code RuntimeException} there leaves the row pending and the whole event is delivered again
 * — including the notification fan-out that already succeeded. Failing a real delivery to
 * retry a cache hint is the wrong way round, so every failure here is a log line.
 *
 * <p><strong>It does not do the call on the caller's thread.</strong> That thread is the
 * scheduler's, shared with every other job in the service, so a web client that is slow to
 * answer would delay the reservation sweep behind it. The call is handed to one worker with a
 * bounded queue.
 *
 * <p><strong>It drops rather than blocks when that queue is full.</strong> A backlog means the
 * far side is unreachable, and a hint that arrives ten minutes late has already been overtaken
 * by the window it was meant to shorten. {@code DiscardPolicy} plus a counted log line is the
 * honest behaviour; a caller that blocked would convert somebody else's outage into ours.
 *
 * <h2>What a disabled deployment does</h2>
 *
 * <p>Nothing, quietly, after saying so once at start-up. See
 * {@link CacheInvalidationProperties#isConfigured()}.
 */
/*
 * NO `@EnableConfigurationProperties` HERE, and the omission is load-bearing. That annotation
 * is an `@Import`, which makes the class carrying it a configuration class — and a
 * configuration class is instantiated during bean-factory post-processing, before Spring
 * Boot's auto-configured beans exist. This one takes a `RestClient.Builder`, so adding it took
 * the whole application context down at start-up with an unrelated bean reported missing.
 *
 * `IdeaNestApplication` already carries `@ConfigurationPropertiesScan`, which binds every
 * `@ConfigurationProperties` record in the tree. There was nothing to enable.
 */
@Component
public class CacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

    /**
     * The far side's own ceiling, restated. Its endpoint refuses a larger batch outright, so
     * splitting here is what stops one busy campaign's invalidation being refused wholesale.
     */
    private static final int MAX_TAGS_PER_CALL = 32;

    private final CacheInvalidationProperties properties;
    private final RestClient client;
    private final ThreadPoolExecutor sender;

    public CacheInvalidator(CacheInvalidationProperties properties, RestClient.Builder builder) {
        this.properties = properties;

        /*
         * The timeouts are set on this client rather than left to the platform default, which
         * is none. A request with no read timeout against a web client that has accepted the
         * connection and stopped answering holds the worker below for ever, and the queue in
         * front of it then fills with hints nobody will send.
         */
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.connectTimeout());
        requests.setReadTimeout(properties.readTimeout());

        this.client = builder.requestFactory(requests).build();
        this.sender = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "cache-invalidator");
                    // A daemon, so a service shutting down is never held open by a hint.
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());

        if (properties.isConfigured()) {
            log.info("Cache invalidation will be sent to {}", properties.endpoint());
        } else {
            log.info("Cache invalidation is off: ideanest.cache.invalidation is not fully configured");
        }
    }

    /**
     * Asks the web client to drop everything filed under these tags.
     *
     * <p>Returns as soon as the work is queued, and returns normally whatever happens to it.
     *
     * @param tags the names, from {@link CacheTags}. Empty is a no-op rather than an error —
     *     an event about a campaign that could not be summarised has nothing to say
     */
    public void invalidate(Collection<String> tags) {
        if (!properties.isConfigured() || tags == null || tags.isEmpty()) {
            return;
        }

        for (List<String> batch : batched(tags)) {
            /*
             * The batch is copied before it is queued. The caller built it from an event it is
             * still translating, and a list that changed between here and the worker would be
             * an invalidation for tags nobody asked for.
             */
            List<String> payload = List.copyOf(batch);
            sender.execute(() -> send(payload));
        }
    }

    private void send(List<String> tags) {
        try {
            client.post()
                    .uri(properties.endpoint())
                    .header("Authorization", "Bearer " + properties.secret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tags", tags))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException failure) {
            /*
             * WARN and not ERROR. The page is stale for up to a minute and then correct on its
             * own, which is a degraded cache rather than a broken platform — and an ERROR for
             * something that self-heals is how a page of them comes to be ignored.
             *
             * The tags are named because the far side answers 400 with the tag it did not
             * recognise, and that is the one failure here that is a defect in this service
             * rather than a network having a bad minute.
             */
            log.warn("Could not invalidate {} on the web client: {}", tags, failure.getMessage());
        }
    }

    private static List<List<String>> batched(Collection<String> tags) {
        List<String> all = List.copyOf(tags);
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < all.size(); from += MAX_TAGS_PER_CALL) {
            batches.add(all.subList(from, Math.min(from + MAX_TAGS_PER_CALL, all.size())));
        }
        return batches;
    }

    /** Stops accepting work and gives whatever is in flight a moment to finish. */
    @PreDestroy
    void stop() {
        sender.shutdown();
        try {
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sender.shutdownNow();
        }
    }
}
