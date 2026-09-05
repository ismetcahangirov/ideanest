package az.ideanest.obligation.application;

import az.ideanest.shared.outbox.OutboxMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * §8.3's two announcements this module needs, and nothing else — issue #437.
 *
 * <p><strong>It listens rather than being called, which is what keeps two other modules from
 * learning that this one exists.</strong> The project module already records
 * {@code project.succeeded} when it applies §5.1, and the community module already records
 * {@code project.update_published} when a creator posts. Neither had to change: a clock that
 * opened itself by being called from {@code CampaignFinalizer} would be an edit to somebody
 * else's transaction for a feature they have no stake in.
 *
 * <p><strong>Redelivery is the contract and not a caveat.</strong> {@code OutboxMessage} says so
 * plainly, and both effects here are naturally idempotent: opening finds the row already there,
 * and recording an update takes only instants later than the one already recorded. So neither
 * needs the event identifier remembered, which is the deduplication this module gets to skip.
 *
 * <p>Synchronous and inside the dispatch transaction, following
 * {@code NotificationEventListener}: an {@code @Async} listener would make every event look
 * delivered the instant it was handed over, which is the one behaviour
 * {@code ApplicationEventOutboxDispatcher} asks an implementation not to have.
 *
 * <h2>Its own reading of somebody else's JSON, deliberately</h2>
 *
 * <p>The two records below are copies of the contract in the sense {@code NotificationEvents} is
 * a copy: this module does not import {@code CampaignFinalisedEvent} or
 * {@code ProjectUpdatePublishedEvent}, because importing them would make a field rename in
 * another module a compile error here and a silent break everywhere else. The field names are the
 * contract; {@code ObligationEventContractTests} asserts them literally, which is the same
 * arrangement {@code CampaignFinalisedEventTests} makes from the other side.
 */
@Component
public class ObligationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ObligationEventListener.class);

    /** §5.1's first branch. An unsuccessful campaign owes nobody an update, and is not listened for. */
    static final String CAMPAIGN_SUCCEEDED = "project.succeeded";

    static final String UPDATE_PUBLISHED = "project.update_published";

    private final UpdateObligations obligations;
    private final ObjectMapper json;

    public ObligationEventListener(UpdateObligations obligations, ObjectMapper json) {
        this.obligations = obligations;
        this.json = json;
    }

    @EventListener
    public void on(OutboxMessage message) {
        switch (message.eventType()) {
            case CAMPAIGN_SUCCEEDED -> {
                CampaignSucceeded event = read(message, CampaignSucceeded.class);
                if (event.projectId() == null || event.creatorId() == null || event.finalisedAt() == null) {
                    // A malformed event is dropped with a line rather than throwing, because
                    // throwing would make the relay retry it forever. What is lost is one
                    // campaign's clock, which a support script can open; what a poison-pill loop
                    // costs is every other event behind it.
                    log.warn("Ignoring a {} with missing fields: {}", CAMPAIGN_SUCCEEDED, message);
                    return;
                }
                obligations.open(event.projectId(), event.creatorId(), event.finalisedAt());
            }
            case UPDATE_PUBLISHED -> {
                UpdatePublished event = read(message, UpdatePublished.class);
                if (event.projectId() == null || event.publishedAt() == null) {
                    log.warn("Ignoring a {} with missing fields: {}", UPDATE_PUBLISHED, message);
                    return;
                }
                obligations.recordUpdate(event.projectId(), event.publishedAt());
            }
            default -> {
                // Every other module's traffic. Not logged: this listener sees every event on the
                // platform, and a line per event would be the log.
            }
        }
    }

    private <T> T read(OutboxMessage message, Class<T> type) {
        try {
            return json.readValue(message.payload(), type);
        } catch (JacksonException unreadable) {
            throw new IllegalStateException("Unreadable payload on " + message, unreadable);
        }
    }

    /**
     * {@code project.succeeded}, as this module reads it.
     *
     * <p>Three fields of six. {@code goal}, {@code pledged} and {@code backersCount} are what the
     * campaign raised and have nothing to say about whether its creator has posted an update —
     * reading them here would be this module holding money it has no use for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CampaignSucceeded(UUID projectId, UUID creatorId, Instant finalisedAt) {
    }

    /**
     * {@code project.update_published}, as this module reads it.
     *
     * <p>{@code publishedAt} and not the recording time: an update scheduled for next week
     * publishes next week, and {@code UpdateObligations.recordUpdate} carries the note about what
     * that means for a creator who schedules several in advance.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpdatePublished(UUID projectId, Instant publishedAt) {
    }
}
