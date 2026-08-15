package az.ideanest.reward.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One atomic thing a campaign produces.
 *
 * <p>§4.6 makes items primary and tiers derived: the same mug appears in four
 * tiers, and its weight, whether it is a file or an object, and the creator's code
 * for it are properties of the mug. The alternative — a free-text list of contents
 * on each tier — was rejected because it cannot answer "how many mugs do I owe",
 * which is the only question fulfilment asks.
 *
 * <p>The campaign is held as an identifier rather than as an association, for the
 * reason {@code Project} gives about its creator: {@code projects} belongs to
 * another module, and a {@code @ManyToOne} to it would reach into that module's
 * domain.
 *
 * <p>Nothing here writes stock. An item is a description; how many exist is a
 * property of the tiers that include it and of the pledges against them.
 */
@Entity
@Table(name = "items")
public class Item {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * INTERIM, and the same interim as the campaign's cover image. There is no
     * media pipeline (§13): this is a URL the client supplied, for a file nothing
     * on the server has seen. It becomes a reference to a {@code media} row when
     * that module lands.
     */
    @Column(name = "image_url")
    private String imageUrl;

    /**
     * Null for a digital item, and the database refuses any other combination.
     * A weight recorded against a file would be summed into a shipping quote for
     * something that is not shipped.
     */
    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "is_digital", nullable = false)
    private boolean digital;

    @Column(name = "sku")
    private String sku;

    /** The database's, as on every other table: a trigger cannot forget. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Item() {
        // JPA.
    }

    private Item(UUID id, UUID projectId, String name) {
        this.id = id;
        this.projectId = projectId;
        this.name = name;
        this.digital = false;
    }

    /**
     * A new item with a name and nothing else.
     *
     * <p>A name is the one thing an item cannot be without: it is what the tier
     * editor lists and what the fulfilment report totals. Everything else is
     * optional because a creator building a reward adds the weight when the
     * prototype is weighed, not when the item is first written down.
     */
    public static Item of(UUID projectId, String name) {
        return new Item(Identifiers.newIdentifier(), projectId, name);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Integer getWeightGrams() {
        return weightGrams;
    }

    public boolean isDigital() {
        return digital;
    }

    /**
     * Sets what the item is and what it weighs, together.
     *
     * <p>Never separately: the database refuses a digital item with a weight, and
     * two setters would let a caller write the halves in an order that violates it
     * — or leave a weight behind after an object became a download.
     */
    public void describePhysicality(boolean digital, Integer weightGrams) {
        if (digital && weightGrams != null) {
            throw new IllegalArgumentException("A digital item has no shipping weight");
        }
        this.digital = digital;
        this.weightGrams = weightGrams;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Item item && Objects.equals(id, item.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No name: an unlaunched campaign's contents are confidential, and this
        // lands in logs.
        return "Item[id=" + id + ", project=" + projectId + "]";
    }
}
