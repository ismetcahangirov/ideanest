package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pledge's parcel — §4.8's PM-20 to PM-22.
 *
 * <p>The same shape as {@link ShippingAddress}: keyed by the pledge, because there is
 * one of these per pledge and every read arrives holding the pledge. V38 argues why a
 * split shipment is not representable and what the expand would be if a campaign ever
 * needs one.
 *
 * <h2>The status decides the timestamps</h2>
 *
 * <p>{@code shippedAt} is set exactly when the status has shipped and {@code
 * deliveredAt} exactly when it is {@link FulfilmentStatus#DELIVERED}. Nothing else may
 * write them — there is no setter — so the pair cannot drift from the value they
 * describe, and V38 holds the same rule in two check constraints for the rows this
 * class did not write.
 *
 * <p><strong>A correction clears the instant it contradicts.</strong> A creator who
 * marks a parcel delivered by mistake and puts it back to {@code SHIPPED} loses the
 * delivery instant, because "delivered at Tuesday" on a parcel that has not been
 * delivered is exactly the row a backer would be shown and believe. What survives the
 * correction is {@code audit_logs}, which records the import that made each claim.
 *
 * <p><strong>The instant is passed in.</strong> Every other entity in this module
 * takes its clock from the service for the same reason: a class that read
 * {@code Instant.now()} would make "a re-import that changes nothing does not move the
 * shipped instant" a rule nobody can test without waiting.
 */
@Entity
@Table(name = "fulfilments")
public class Fulfilment {

    @Id
    @Column(name = "pledge_id", nullable = false, updatable = false)
    private UUID pledgeId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FulfilmentStatus status;

    @Column(name = "carrier")
    private String carrier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Fulfilment() {
        // JPA.
    }

    /** The first time a creator says anything about this pledge's parcel. */
    public static Fulfilment of(
            UUID pledgeId,
            UUID projectId,
            FulfilmentStatus status,
            Tracking tracking,
            UUID importedBy,
            Instant at) {

        Fulfilment fulfilment = new Fulfilment();
        fulfilment.pledgeId = Objects.requireNonNull(pledgeId, "A fulfilment is of a pledge");
        fulfilment.projectId = Objects.requireNonNull(projectId, "A fulfilment belongs to a campaign");
        fulfilment.apply(status, tracking, importedBy, at);
        return fulfilment;
    }

    /**
     * What the latest import says about this parcel.
     *
     * <p>A whole statement, not a patch: the row after this call says exactly what the
     * import row said, and an omitted carrier clears the carrier. Merging would be the
     * shape in which a corrected row keeps the tracking number it was corrected to
     * remove, and a creator who cannot remove a wrong number has to email every backer
     * it went to.
     *
     * @param at the instant this import ran, stamped on the transitions it causes
     * @return whether anything actually changed, which is what lets an import report
     *     the rows it touched rather than the rows it read
     */
    public boolean apply(FulfilmentStatus status, Tracking tracking, UUID importedBy, Instant at) {
        Objects.requireNonNull(status, "A parcel is in some status");
        Objects.requireNonNull(tracking, "Tracking.none() is the empty value, not null");
        Objects.requireNonNull(at, "An import happened at a time");

        Tracking current = tracking();
        if (this.status == status && current.equals(tracking)) {
            // Re-importing yesterday's file is the ordinary case — a creator adds
            // fifty rows to a spreadsheet and uploads the whole thing again — and it
            // must not restamp four thousand rows, both because `updated_at` is what
            // a creator sorts by and because the write is the expensive half.
            return false;
        }

        this.status = status;
        this.carrier = tracking.carrier();
        this.trackingNumber = tracking.number();
        this.trackingUrl = tracking.url();
        this.updatedBy = importedBy;

        // The instants follow the status rather than being reported alongside it. See
        // the class comment for what that costs on a correction and why it is right.
        if (!status.hasShipped()) {
            this.shippedAt = null;
        } else if (this.shippedAt == null) {
            this.shippedAt = at;
        }
        this.deliveredAt = status == FulfilmentStatus.DELIVERED ? deliveredInstant(at) : null;
        return true;
    }

    /** Keeps the first delivery instant when a row is re-imported as delivered again. */
    private Instant deliveredInstant(Instant at) {
        return deliveredAt == null ? at : deliveredAt;
    }

    /** The three tracking fields as the value they only mean anything as. */
    public Tracking tracking() {
        return new Tracking(carrier, trackingNumber, trackingUrl);
    }

    public UUID getPledgeId() {
        return pledgeId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public FulfilmentStatus getStatus() {
        return status;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getTrackingUrl() {
        return trackingUrl;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fulfilment fulfilment && Objects.equals(pledgeId, fulfilment.pledgeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pledgeId);
    }

    @Override
    public String toString() {
        // No tracking number: a log line about a parcel should not be a record of
        // where somebody's parcel is.
        return "Fulfilment[pledge=" + pledgeId + ", status=" + status + "]";
    }
}
