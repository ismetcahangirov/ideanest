package az.ideanest.staff.infrastructure;

import az.ideanest.staff.domain.StaffRole;
import az.ideanest.staff.domain.StaffRoleGrant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V48's grants, by the three questions asked of them — #295.
 *
 * <p><strong>The first is on the hot path of every console request</strong> and is why
 * this table is shaped the way it is: {@link #rolesOf} is one index lookup on the primary
 * key's leading column, returning at most four rows. {@code StaffDirectory} caches nothing
 * — a role withdrawn has to stop working on the next request, not on the next restart,
 * and this query is cheap enough that a cache would be trading correctness for a lookup
 * that is already a primary key hit.
 *
 * <p>The insert is {@code ON CONFLICT DO NOTHING} rather than a {@code save}, for
 * {@code ContentReportRepository}'s reason: two administrators granting the same role at
 * once both see no row, both insert, and one of them gets a constraint violation on a
 * request that should have succeeded. Re-granting a role somebody already holds is not an
 * error; it is a no-op with a slightly disappointing audit entry.
 *
 * <p><strong>{@code DO NOTHING} rather than {@code DO UPDATE}</strong> so a second grant
 * cannot rewrite {@code granted_by} on the first. Who let this person in is answered by
 * the grant that is standing, and an upsert would let the second administrator quietly
 * become the answer.
 */
public interface StaffRoleRepository extends JpaRepository<StaffRoleGrant, StaffRoleGrant.Key> {

    /** Every role this account holds. At most one row per {@link StaffRole}. */
    @Query("SELECT g FROM StaffRoleGrant g WHERE g.accountId = :accountId")
    List<StaffRoleGrant> rolesOf(@Param("accountId") UUID accountId);

    /**
     * Everybody who holds anything, oldest grant first.
     *
     * <p>Unpaged, and that is a decision rather than an oversight: this table holds one
     * row per role per member of staff, the platform has four, and a keyset cursor over
     * four rows is ceremony. The day it is not four, this method grows a {@code Pageable}
     * and the screen grows a cursor — and it will be obvious, because the screen will be
     * long.
     */
    @Query("SELECT g FROM StaffRoleGrant g ORDER BY g.grantedAt ASC, g.accountId ASC")
    List<StaffRoleGrant> everyGrant();

    /**
     * Records a grant, or does nothing because the account already holds the role.
     *
     * <p>Native because JPQL has no {@code ON CONFLICT}. The role arrives as a string
     * because the column is {@code text} with a {@code CHECK} rather than a PostgreSQL
     * enum type — V48's header has why.
     *
     * @return 1 when this call created the grant, 0 when it was already there
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO staff_role_grants (account_id, role, granted_by, note)
                    VALUES (:accountId, :role, :grantedBy, :note)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int grantIfAbsent(
            @Param("accountId") UUID accountId,
            @Param("role") String role,
            @Param("grantedBy") UUID grantedBy,
            @Param("note") String note);

    /**
     * Withdraws a role.
     *
     * @return 1 when a grant was withdrawn, 0 when the account did not hold it. The
     *     caller uses it to tell "I did this" from "somebody already had", which is the
     *     difference between an audit entry worth writing and one that would say nothing
     */
    @Modifying
    @Query("DELETE FROM StaffRoleGrant g WHERE g.accountId = :accountId AND g.role = :role")
    int revoke(@Param("accountId") UUID accountId, @Param("role") StaffRole role);
}
