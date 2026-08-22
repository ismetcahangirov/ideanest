package az.ideanest.reward.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One destination inside one zone.
 *
 * <p><strong>Keyed by the campaign and the country, not by the zone.</strong> That
 * is the whole of #77's precedence argument made structural: a country belongs to
 * at most one zone per campaign, so resolving a destination finds one zone or none
 * and never two that disagree. Keying it by zone would permit an overlap, and an
 * overlap needs a priority column — which is a thing creators get wrong in a way
 * that costs them money on every parcel.
 *
 * <p>The consequence a creator meets is that adding Germany to a second zone is
 * refused rather than silently preferred, and the message says which zone already
 * has it.
 */
@Entity
@Table(name = "shipping_zone_countries")
public class ShippingZoneCountry {

    /** The campaign and the destination. See {@code ShippingRule.Key} for why it is a class. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "project_id", nullable = false, updatable = false)
        private UUID projectId;

        @Column(name = "country_code", nullable = false, updatable = false)
        private String countryCode;

        protected Key() {
            // JPA.
        }

        public Key(UUID projectId, String countryCode) {
            this.projectId = Objects.requireNonNull(projectId, "A zone membership names its campaign");
            this.countryCode = Objects.requireNonNull(countryCode, "A zone membership names its destination");
        }

        public UUID getProjectId() {
            return projectId;
        }

        public String getCountryCode() {
            return countryCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof Key key
                    && Objects.equals(projectId, key.projectId)
                    && Objects.equals(countryCode, key.countryCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, countryCode);
        }

        @Override
        public String toString() {
            return "Key[project=" + projectId + ", country=" + countryCode + "]";
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "zone_id", nullable = false)
    private UUID zoneId;

    protected ShippingZoneCountry() {
        // JPA.
    }

    /** @param countryCode normalised by {@link ShippingZone#normaliseCountry} before it reaches here */
    public static ShippingZoneCountry of(UUID zoneId, UUID projectId, String countryCode) {
        ShippingZoneCountry membership = new ShippingZoneCountry();
        membership.id = new Key(projectId, ShippingZone.normaliseCountry(countryCode));
        membership.zoneId = Objects.requireNonNull(zoneId, "A membership belongs to a zone");
        return membership;
    }

    public UUID getProjectId() {
        return id.getProjectId();
    }

    public String getCountryCode() {
        return id.getCountryCode();
    }

    public UUID getZoneId() {
        return zoneId;
    }

    /**
     * Moves this destination to another zone.
     *
     * <p>Exists for the same reason {@code ShippingRule.reprice} does: a country
     * present in both the old and the new membership of a campaign keeps its row
     * rather than being deleted and inserted again, which Hibernate would order the
     * wrong way round and the primary key would refuse in between.
     */
    public void moveTo(UUID zoneId) {
        this.zoneId = Objects.requireNonNull(zoneId, "A membership belongs to a zone");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ShippingZoneCountry membership && Objects.equals(id, membership.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ShippingZoneCountry[" + id + " -> " + zoneId + "]";
    }
}
