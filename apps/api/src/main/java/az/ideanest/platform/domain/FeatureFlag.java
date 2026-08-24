package az.ideanest.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One switch — V50's row, issue #312.
 *
 * <p><strong>The evaluation lives here rather than in the service</strong>, which is
 * unusual for this codebase and is the right place for exactly one reason: it has to be
 * the same function every time. A rollout percentage is only stable if the same account
 * always lands on the same side of it, and a hash computed in two places is a hash that
 * eventually differs — at which point somebody who saw the feature yesterday does not
 * today, for no reason anybody can find.
 *
 * <p>{@code enabledAccounts} is a PostgreSQL array rather than a join table. V50's header
 * has the argument: the list is read in full on every evaluation, never queried from the
 * other direction, and rewritten whole by the screen that edits it.
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "rollout_percentage", nullable = false)
    private short rolloutPercentage;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enabled_accounts", nullable = false)
    private UUID[] enabledAccounts;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected FeatureFlag() {
        // Hibernate.
    }

    public FeatureFlag(
            String key,
            String description,
            boolean enabled,
            short rolloutPercentage,
            List<UUID> enabledAccounts,
            UUID updatedBy) {

        this.key = Objects.requireNonNull(key, "key");
        this.description = Objects.requireNonNull(description, "description");
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.enabledAccounts = enabledAccounts.toArray(UUID[]::new);
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
    }

    /**
     * Replaces everything about the flag except its name.
     *
     * <p>One method rather than a setter per column, so that an edit is one statement and
     * one audit row. A screen that could change the percentage without saying who did it
     * is a screen whose changes cannot be reviewed.
     */
    public void update(
            String description, boolean enabled, short rolloutPercentage, List<UUID> enabledAccounts, UUID by) {

        this.description = Objects.requireNonNull(description, "description");
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.enabledAccounts = enabledAccounts.toArray(UUID[]::new);
        this.updatedBy = Objects.requireNonNull(by, "by");
    }

    /**
     * Whether this account sees the feature.
     *
     * <p><strong>{@code enabled} is checked first and it is a kill switch.</strong> An
     * account on the explicit list does not see a disabled flag — "I turned it off and it
     * is still on for some people" is the worst possible property of a switch somebody
     * reaches for during an incident, and V50's header says the same thing about the
     * column.
     *
     * <p>The percentage is decided by hashing the pair rather than by sampling a set.
     * Hashing is stable, so widening a rollout only ever adds people; a stored sample has
     * to be recomputed when the percentage moves, and every recomputation takes the
     * feature away from somebody who had it.
     *
     * <p>{@code Objects.hash} is deliberately not used: its result is not specified across
     * releases, and a rollout that reshuffled on a JDK upgrade would be exactly the defect
     * this method is written to avoid. {@link String#hashCode} and {@link UUID#hashCode}
     * are both specified in their contracts.
     */
    public boolean isOnFor(UUID accountId) {
        if (!enabled) {
            return false;
        }
        if (rolloutPercentage >= 100) {
            return true;
        }
        if (accountId == null) {
            // A signed-out visitor has no stable identity to hash, and inventing one from
            // the request would make the flag flicker between page loads. They get the
            // feature only when it is on for everybody.
            return false;
        }
        for (UUID enabledAccount : enabledAccounts) {
            if (enabledAccount.equals(accountId)) {
                return true;
            }
        }
        if (rolloutPercentage <= 0) {
            return false;
        }

        int bucket = Math.floorMod(31 * key.hashCode() + accountId.hashCode(), 100);
        return bucket < rolloutPercentage;
    }

    public String key() {
        return key;
    }

    public String description() {
        return description;
    }

    public boolean enabled() {
        return enabled;
    }

    public short rolloutPercentage() {
        return rolloutPercentage;
    }

    public List<UUID> enabledAccounts() {
        return List.of(enabledAccounts);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID updatedBy() {
        return updatedBy;
    }
}
