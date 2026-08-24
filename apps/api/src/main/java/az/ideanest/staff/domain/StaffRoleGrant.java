package az.ideanest.staff.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One account holding one role — V48's row, as #295 writes it.
 *
 * <p>Like {@code AuditEntry}, there is no setter and no second constructor: a grant is
 * made or withdrawn and is never edited. Changing the note on a grant somebody else
 * issued would rewrite the record of why they issued it, and re-granting is one row
 * deleted and one written, which is two audit entries rather than an update nobody sees.
 *
 * <p>{@code granted_at} is the database's, for {@code AuditEntry}'s reason: an instant
 * no caller supplies is an instant no caller can backdate.
 */
@Entity
@Table(name = "staff_role_grants")
@IdClass(StaffRoleGrant.Key.class)
public class StaffRoleGrant {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false)
    private StaffRole role;

    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", nullable = false, updatable = false)
    private UUID grantedBy;

    @Column(name = "note", updatable = false)
    private String note;

    protected StaffRoleGrant() {
        // Hibernate.
    }

    public StaffRoleGrant(UUID accountId, StaffRole role, UUID grantedBy, String note) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.role = Objects.requireNonNull(role, "role");
        this.grantedBy = Objects.requireNonNull(grantedBy, "grantedBy");
        this.note = note;
    }

    public UUID accountId() {
        return accountId;
    }

    public StaffRole role() {
        return role;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    public UUID grantedBy() {
        return grantedBy;
    }

    public String note() {
        return note;
    }

    /** The composite key. Required by JPA; carries no behaviour. */
    public static class Key implements Serializable {

        private UUID accountId;

        private StaffRole role;

        protected Key() {
        }

        public Key(UUID accountId, StaffRole role) {
            this.accountId = accountId;
            this.role = role;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(accountId, key.accountId)
                    && role == key.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, role);
        }
    }
}
