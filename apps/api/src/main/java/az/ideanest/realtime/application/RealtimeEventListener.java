package az.ideanest.realtime.application;

import az.ideanest.realtime.domain.RealtimeChannel;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.outbox.OutboxMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * How this module hears that something happened, and the one rule it lives by.
 *
 * <p>A listener on {@code OutboxMessage}, exactly as {@code NotificationEventListener} is, and
 * for the same reason: the dispatcher stays untyped so no per-event Java class becomes a
 * compile-time coupling between the relay and every module's events. Nothing here imports
 * anything from another module.
 *
 * <h2>This consumer may never fail a dispatch</h2>
 *
 * <p><strong>It swallows everything, and that is the opposite of what the notification module
 * does.</strong> That module throws on a payload it cannot read, because a producer and a
 * consumer disagreeing about an event is a fault somebody has to see and because a notification
 * nobody receives is its worst failure. This module's worst failure is a counter that did not
 * move for a second.
 *
 * <p>The dispatcher publishes one message to every listener inside one transaction, so throwing
 * here would roll back the pledge confirmation's dispatch, take the notification module's rows
 * with it, and dead-letter the event on the eighth attempt — destroying somebody's confirmation
 * email over a live counter. So every branch below is best-effort and every failure is a log
 * line.
 *
 * <p>{@code NotificationFanOut} makes the same argument about a recipient who is not an account:
 * a consumer must not be able to veto an event for every other consumer. It applies to this
 * module for every failure rather than for one.
 *
 * <h2>Nothing is broadcast from here</h2>
 *
 * <p>This runs on the relay's thread, inside the dispatch transaction. A send from here would be
 * a socket write inside a database transaction — and, worse, a viewer told about a pledge that
 * then rolled back. What happens here is one map update; {@link RealtimeFlusher} broadcasts on
 * its own tick, after the commit.
 */
@Component
public class RealtimeEventListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventListener.class);

    /** The events this module reacts to. Both are already published for other reasons. */
    private static final String PLEDGE_CONFIRMED = "pledge.confirmed";

    private static final String COMMENT_POSTED = "comment.posted";

    private final RealtimeAggregator aggregator;
    private final ObjectMapper json;

    public RealtimeEventListener(RealtimeAggregator aggregator, ObjectMapper json) {
        this.aggregator = aggregator;
        this.json = json;
    }

    @EventListener
    public void on(OutboxMessage message) {
        try {
            switch (message.eventType()) {
                case PLEDGE_CONFIRMED -> {
                    PledgeConfirmed event = json.readValue(message.payload(), PledgeConfirmed.class);
                    if (event != null && event.projectId() != null) {
                        aggregator.record(
                                new RealtimeChannel(RealtimeChannel.Kind.PROJECT, event.projectId()), event.total());
                    }
                }
                case COMMENT_POSTED -> {
                    CommentPosted event = json.readValue(message.payload(), CommentPosted.class);
                    if (event != null && event.projectId() != null) {
                        aggregator.recordComment(
                                new RealtimeChannel(RealtimeChannel.Kind.COMMENTS, event.projectId()),
                                event.commentId());
                    }
                }
                default -> {
                    // Every other module's traffic. Ignored silently, which is the only correct
                    // answer: the dispatcher publishes every event to every listener.
                }
            }
        } catch (RuntimeException e) {
            // Everything, including Jackson's own -- unchecked in Jackson 3, so one catch
            // covers a payload this module cannot read and anything else that goes wrong.
            //
            // See the class comment. A live counter is never worth failing somebody's pledge
            // confirmation over, so this is where every failure in this module stops.
            log.warn("Could not turn {} into a live update; the counter misses this one", message, e);
        }
    }

    /**
     * This module's reading of {@code pledge.confirmed}.
     *
     * <p>A third copy of that contract — the pledge module writes it, the notification module
     * declares its own reading, and this declares a narrower one still. Neither imports the
     * other, which is the arrangement, and the cost is that renaming a field breaks three
     * consumers without breaking any compilation.
     *
     * <p>Two fields of five, deliberately: this module needs the campaign to route to and the
     * amount to add up. It has no use for the backer, and a live counter should not be holding
     * one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PledgeConfirmed(UUID projectId, Money total) {
    }

    /** This module's reading of {@code comment.posted}. The campaign, and which comment. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommentPosted(UUID projectId, UUID commentId) {
    }
}
