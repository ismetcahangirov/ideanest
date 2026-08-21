package az.ideanest.realtime.application;

import az.ideanest.realtime.RealtimeProperties;
import az.ideanest.realtime.domain.RealtimeChannel;
import az.ideanest.shared.money.CurrencyMismatchException;
import az.ideanest.shared.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §12.1's one-second windows: the whole of what #91 asks for.
 *
 * <p>"On high-traffic projects the pledge counter is <strong>aggregated into one-second windows
 * before broadcast</strong>, rather than emitting an event per pledge." The failure that removes
 * is not bandwidth, which is trivial either way — it is that a page taking forty pledges a second
 * would re-render its counter forty times a second per viewer, and a number changing at that rate
 * is not a number anybody can read. So the counter moves once a second, by however much arrived.
 *
 * <h2>Accumulate here, flush on a timer</h2>
 *
 * <p>Events arrive on the outbox relay's thread, inside a dispatch transaction shared with every
 * other consumer. Nothing here may block it and nothing here may fail it, so
 * {@link #record(RealtimeChannel, Money)} does one map update and returns. The broadcast happens
 * on {@link RealtimeFlushJob}'s tick, outside that transaction and after it has committed —
 * which is also the only ordering in which a viewer cannot be shown a pledge that then rolls
 * back.
 *
 * <p><strong>A {@link ConcurrentHashMap} and {@code compute}, not a lock.</strong> The relay may
 * dispatch on more than one thread and the flush runs on another; what has to be atomic is
 * "add this to whatever is pending", which {@code compute} gives per key without any of them
 * waiting on a channel they are not writing to.
 *
 * <h2>What is not aggregated, and what is not sent at all</h2>
 *
 * <p>Comments are aggregated too, which §12.1 does not ask for and which is the same argument one
 * step further: a busy campaign's comments arrive in bursts, and a page that inserted a banner
 * per comment would do the same thing to the reader that the unaggregated counter does.
 *
 * <p><strong>No comment body is ever broadcast, and that is a safety decision rather than a size
 * one.</strong> A comment can be removed by its author, by the campaign's team (CD-14) or by
 * moderation (AD-09) seconds after it is posted; the removal is a tombstone that every read path
 * honours. This path has no read and no way to take a message back, so pushing the text would be
 * publishing content past every control the platform has for removing it. What goes out is a
 * count and the newest identifier, and the client fetches through the ordinary endpoint — which
 * honours the tombstone.
 */
@Component
public class RealtimeAggregator {

    private static final Logger log = LoggerFactory.getLogger(RealtimeAggregator.class);

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final RealtimeProperties properties;

    public RealtimeAggregator(RealtimeProperties properties) {
        this.properties = properties;
    }

    /**
     * Adds a confirmed pledge to the current window for its campaign.
     *
     * @param amount what was pledged, or null when the event did not carry one. A pledge with no
     *     amount still moves the count: the counter a page shows is two numbers, and "somebody
     *     backed this" is the one that is never wrong
     */
    public void record(RealtimeChannel channel, Money amount) {
        if (!properties.enabled()) {
            return;
        }
        pending.compute(channel.name(), (name, current) -> (current == null ? Pending.empty() : current).plusPledge(amount));
    }

    /** Adds a posted comment to the current window for its campaign. */
    public void recordComment(RealtimeChannel channel, UUID commentId) {
        if (!properties.enabled()) {
            return;
        }
        pending.compute(
                channel.name(), (name, current) -> (current == null ? Pending.empty() : current).plusComment(commentId));
    }

    /**
     * Takes everything accumulated since the last call and empties the buffer.
     *
     * <p><strong>Removed rather than read and cleared.</strong> {@code remove} is atomic against
     * a concurrent {@code compute} on the same key: an event arriving during the flush either
     * lands in the window being taken or starts the next one, and never lands in a window that
     * has already been sent and is about to be zeroed.
     *
     * <p>Channels with nothing pending are absent rather than present and empty, so a quiet
     * campaign costs one map with no entry in it rather than a message per second per campaign
     * anybody has ever looked at.
     */
    public List<ChannelWindow> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }

        List<ChannelWindow> windows = new ArrayList<>();
        // Iterating the key set and removing each is safe on a ConcurrentHashMap and is what
        // makes the take atomic per channel. A `clear()` after building the list would drop
        // whatever arrived in between.
        for (String channel : List.copyOf(pending.keySet())) {
            Pending window = pending.remove(channel);
            if (window == null || window.isEmpty()) {
                continue;
            }
            windows.add(new ChannelWindow(
                    channel, window.pledges, window.amount, window.comments, window.latestCommentId));
        }
        return windows;
    }

    /** How many channels have something waiting. For the flush job's log line and for tests. */
    public int pendingChannels() {
        return pending.size();
    }

    /**
     * One channel's accumulation, immutable so that {@code compute} can replace it wholesale.
     *
     * <p>Immutable rather than a mutable accumulator, which would be the obvious choice and is
     * the wrong one here: {@code compute}'s atomicity guarantee is about the mapping, not about
     * what the value does after it is handed out, so a mutable value read by the flush while the
     * relay adds to it would be exactly the race the map was chosen to avoid.
     */
    private record Pending(int pledges, Money amount, int comments, UUID latestCommentId) {

        static Pending empty() {
            return new Pending(0, null, 0, null);
        }

        boolean isEmpty() {
            return pledges == 0 && comments == 0;
        }

        Pending plusPledge(Money added) {
            return new Pending(pledges + 1, sum(amount, added), comments, latestCommentId);
        }

        Pending plusComment(UUID commentId) {
            return new Pending(pledges, amount, comments + 1, commentId == null ? latestCommentId : commentId);
        }

        /**
         * Two amounts, or the one that exists.
         *
         * <p><strong>A campaign has one currency</strong> — §10.3, and the pledge module enforces
         * it — so a mismatch here is not a case to handle but a fact that has stopped being true.
         * It is caught and the running amount is dropped rather than thrown, because throwing
         * would happen on the relay's thread inside a dispatch every other consumer shares: a
         * live counter is not worth failing a pledge confirmation over. The count survives, so
         * the page still moves.
         */
        private static Money sum(Money running, Money added) {
            if (added == null) {
                return running;
            }
            if (running == null) {
                return added;
            }
            try {
                return running.plus(added);
            } catch (CurrencyMismatchException impossible) {
                log.warn(
                        "Two currencies arrived in one campaign's window; the live amount for it is dropped for"
                                + " this window and the count is not",
                        impossible);
                return null;
            }
        }
    }
}
