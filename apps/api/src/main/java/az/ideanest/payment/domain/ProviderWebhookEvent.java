package az.ideanest.payment.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * §9.3's R-07 and §17.2, as a row: one verified provider delivery (#66).
 *
 * <p>Written in the same transaction as whatever the delivery caused, which is the
 * whole of the exactly-once guarantee — V43 has the argument. The unique index over
 * {@code (provider, provider_event_id)} is what makes a redelivery harmless: the insert
 * is refused, the ingestion catches the refusal, and the response is a 200 with nothing
 * done a second time.
 *
 * <p><strong>Insert only, by construction rather than by trigger.</strong> Unlike
 * {@code transactions} and {@code ledger_entries} this table gets no append-only
 * trigger, because it is not a financial record: it is a log of what a third party
 * said, and its value is the deduplication rather than the evidence. Every column is
 * still {@code updatable = false} and there are no setters, because a row here
 * describes one moment and has nothing to move to.
 */
@Entity
@Table(name = "provider_webhook_events")
public class ProviderWebhookEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private ProviderName provider;

    /** The provider's own identifier for the event. Half of the deduplication key. */
    @Column(name = "provider_event_id", nullable = false, updatable = false)
    private String providerEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private PaymentEventType eventType;

    /**
     * The body as it arrived, verbatim.
     *
     * <p>{@code text} and not {@code jsonb}, which is the opposite of the choice
     * {@code transactions.provider_response} makes, and V43 has the reason: this is the
     * evidence in a dispute — the bytes that were signed — and {@code jsonb} would
     * re-serialise them into a document the signature no longer verifies against.
     */
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    /** When the provider says it signed. Null when the provider's format carries no timestamp. */
    @Column(name = "provider_signed_at", updatable = false)
    private Instant providerSignedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private Instant receivedAt;

    /** When the handler finished, from the injected {@code Clock}. See V43 for why two columns. */
    @Column(name = "handled_at", nullable = false, updatable = false)
    private Instant handledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, updatable = false)
    private WebhookEventState state;

    /** What the handler did, in one line, for the support conversation. Null on an ignored row. */
    @Column(name = "outcome", updatable = false)
    private String outcome;

    protected ProviderWebhookEvent() {
        // Hibernate's.
    }

    private ProviderWebhookEvent(PaymentEvent event, WebhookEventState state, String outcome, Instant handledAt) {
        this.id = Identifiers.newIdentifier();
        this.provider = event.provider();
        this.providerEventId = event.providerEventId();
        this.eventType = event.type();
        this.payload = event.rawBody();
        this.providerSignedAt = event.signedAt();
        this.state = state;
        this.outcome = truncate(outcome);
        this.handledAt = handledAt;
    }

    /** A delivery a handler recognised and acted on. */
    public static ProviderWebhookEvent processed(PaymentEvent event, String outcome, Instant handledAt) {
        return new ProviderWebhookEvent(event, WebhookEventState.PROCESSED, outcome, handledAt);
    }

    /** A delivery the platform verified, recorded, and had nothing to do about. */
    public static ProviderWebhookEvent ignored(PaymentEvent event, Instant handledAt) {
        return new ProviderWebhookEvent(event, WebhookEventState.IGNORED, null, handledAt);
    }

    /** V43 bounds the column at 500; a handler's sentence is not bounded by anything. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    public UUID getId() {
        return id;
    }

    public ProviderName getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public PaymentEventType getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getProviderSignedAt() {
        return providerSignedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public WebhookEventState getState() {
        return state;
    }

    public String getOutcome() {
        return outcome;
    }
}
