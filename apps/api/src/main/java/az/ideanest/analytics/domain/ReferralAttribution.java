package az.ideanest.analytics.domain;

import az.ideanest.shared.Identifiers;
import az.ideanest.shared.money.Money;
import az.ideanest.shared.money.MoneyAmountConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pledge, and where it came from. The answer, decided once.
 *
 * <p><strong>Nothing here changes after it is written</strong>, and every column says
 * so: a creator who read "the newsletter brought forty pledges" last week has to read
 * the same number this week, and a report whose history moves is a report nobody can
 * act on. That is enforced the way {@code AuditEntry} enforces it — no setters, every
 * column {@code updatable = false} — with the difference that this table has no
 * append-only trigger behind it. It does not need one: nothing is privileged about an
 * attribution, and the row it would protect is derived rather than evidential.
 *
 * <p><strong>The source is copied, not referenced.</strong> {@link #getTouchId()} says
 * which visit won, for an operator asking why the row says what it says, and the
 * channel and its labels are stored again beside it. That duplication is deliberate:
 * a touch is prunable the moment its window closes, and the report must not move when
 * one is pruned. V24's header makes the argument in full.
 *
 * <p><strong>There is nowhere to put a backer.</strong> The report this feeds is read
 * by a creator, and a creator able to see that the pledge attributed to
 * "instagram, launch-week" belongs to a named person has been told exactly what §4.5's
 * PL-12 spends a column on not telling them. A projection that omitted the field would
 * be one review away from including it; a row with no field cannot.
 */
@Entity
@Table(name = "referral_attributions")
public class ReferralAttribution {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Which pledge. Unique in the schema, which is how the listener tolerates
     * redelivery — see {@code ReferralAttributionService}.
     */
    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Null for a pledge attributed to DIRECT, and null again once the touch is pruned. */
    @Column(name = "touch_id", updatable = false)
    private UUID touchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    private ReferralChannel channel;

    @Column(name = "source", updatable = false)
    private String source;

    @Column(name = "campaign", updatable = false)
    private String campaign;

    @Column(name = "referrer_code", updatable = false)
    private String referrerCode;

    /**
     * {@code numeric(14,2)}, held to {@code Money}'s rules on the way in and out.
     *
     * <p>The amount and the currency are two columns and are assembled into a
     * {@link Money} at the edge of this entity, which is what
     * {@code MoneyAmountConverter} exists to make safe. Never a double: this column is
     * summed into the figure a creator uses to decide where to spend next.
     */
    @Convert(converter = MoneyAmountConverter.class)
    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "pledged_at", nullable = false, updatable = false)
    private Instant pledgedAt;

    @Column(name = "attributed_at", nullable = false, updatable = false)
    private Instant attributedAt;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    protected ReferralAttribution() {
        // JPA.
    }

    /**
     * The attribution for one pledge.
     *
     * @param touchId the visit that won, or null when the rule found none and the
     *     pledge is therefore direct
     * @param source what that visit said, or {@link ReferralSource#direct()}
     * @param value the pledge's total. Positive, which
     *     {@code referral_attributions_amount_is_positive} also insists on: a
     *     non-positive attributed value would make every other row's share of the
     *     total meaningless
     * @param pledgedAt when the pledge was confirmed — the instant the rule was
     *     applied against, and not when this row happened to be written
     * @param eventId the delivery this was written from, kept so that an operator can
     *     tell a redelivery from a second event
     */
    public static ReferralAttribution record(
            UUID pledgeId,
            UUID projectId,
            UUID touchId,
            ReferralSource source,
            Money value,
            Instant pledgedAt,
            Instant attributedAt,
            UUID eventId) {

        ReferralAttribution attribution = new ReferralAttribution();
        attribution.id = Identifiers.newIdentifier();
        attribution.pledgeId = Objects.requireNonNull(pledgeId, "An attribution is about a pledge");
        attribution.projectId = Objects.requireNonNull(projectId, "An attribution belongs to a campaign");
        attribution.touchId = touchId;

        Objects.requireNonNull(source, "An attribution says where the pledge came from");
        attribution.channel = source.channel();
        attribution.source = source.source();
        attribution.campaign = source.campaign();
        attribution.referrerCode = source.referrerCode();

        Objects.requireNonNull(value, "An attribution carries the value it attributed");
        if (!value.isPositive()) {
            throw new IllegalArgumentException("An attributed pledge is worth something, and this one is " + value);
        }
        attribution.amount = value.amount();
        attribution.currency = value.currency();

        attribution.pledgedAt = Objects.requireNonNull(pledgedAt, "A pledge was confirmed at some moment");
        attribution.attributedAt = Objects.requireNonNull(attributedAt, "A row was written at some moment");
        attribution.eventId = Objects.requireNonNull(eventId, "An attribution names the delivery it came from");
        return attribution;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPledgeId() {
        return pledgeId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTouchId() {
        return touchId;
    }

    public ReferralSource getSource() {
        return new ReferralSource(channel, source, campaign, referrerCode);
    }

    public Money getValue() {
        return Money.of(amount, currency);
    }

    public Instant getPledgedAt() {
        return pledgedAt;
    }

    public Instant getAttributedAt() {
        return attributedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ReferralAttribution attribution && Objects.equals(id, attribution.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No amount. A pledge's value is one person's financial detail, and this
        // record ends up in log lines about deliveries.
        return "ReferralAttribution[id=" + id + ", pledgeId=" + pledgeId + ", channel=" + channel + "]";
    }
}
