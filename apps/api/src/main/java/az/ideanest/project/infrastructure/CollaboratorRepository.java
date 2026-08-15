package az.ideanest.project.infrastructure;

import az.ideanest.project.domain.Collaborator;
import az.ideanest.shared.EmailAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Invitations and grants, by the four questions actually asked of them.
 *
 * <p>There is deliberately no {@code deleteBy...}. A grant is withdrawn by
 * stamping {@code revoked_at}, and an invitation is spent by stamping
 * {@code accepted_at}: a delete would erase the record that somebody had access
 * to an unlaunched campaign, which is the record that matters after a leak.
 */
public interface CollaboratorRepository extends JpaRepository<Collaborator, UUID> {

    /**
     * The grant that authorises this account on this campaign, if there is one.
     *
     * <p>Run on every editor request made by somebody who is not the creator, and
     * backed by {@code collaborators_active_grant_idx}. Accepted and unrevoked is
     * the definition of active, and it is applied here rather than in Java so that
     * a revoked grant cannot be loaded and then asked whether it counts.
     */
    @Query(
            """
            SELECT c FROM Collaborator c
            WHERE c.projectId = :projectId
              AND c.accountId = :accountId
              AND c.acceptedAt IS NOT NULL
              AND c.revokedAt IS NULL
            """)
    Optional<Collaborator> findActiveGrant(
            @Param("projectId") UUID projectId, @Param("accountId") UUID accountId);

    /**
     * Everybody on a campaign, oldest first, including pending invitations and
     * revoked grants.
     *
     * <p>Revoked rows are returned on purpose: "who used to have access" is a
     * question the People tab is asked, and a list that quietly hides them would
     * make a revocation look like it never happened.
     */
    List<Collaborator> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /**
     * The live invitation or grant for an address on a campaign.
     *
     * <p>Mirrors {@code collaborators_live_invitation_key}. Asked before an
     * invitation is issued, so that a second invitation to the same address is a
     * 409 the creator can understand rather than a constraint violation and a 500.
     */
    @Query(
            """
            SELECT c FROM Collaborator c
            WHERE c.projectId = :projectId
              AND c.invitedEmail = :email
              AND c.revokedAt IS NULL
            """)
    Optional<Collaborator> findLiveByEmail(
            @Param("projectId") UUID projectId, @Param("email") EmailAddress email);

    /** The row a presented invitation token belongs to, found by its hash. */
    Optional<Collaborator> findByInvitationTokenHash(byte[] invitationTokenHash);

    /**
     * Spends an invitation, if it is still unspent.
     *
     * <p>A conditional update rather than a read followed by a write, for the
     * reason {@code EmailVerificationService} gives: two clicks arriving together
     * would both see an unaccepted invitation and both proceed, and only the
     * database can decide which one wins — but only if the condition is part of
     * the statement.
     *
     * @return 1 when this call is the one that spent it, 0 when somebody else did
     */
    // The persistence context is cleared afterwards, because the row this statement
    // changed is also loaded as an entity: without it the caller's next read would
    // come from the first-level cache and would still describe a pending
    // invitation, which is precisely the state the caller has just left.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            UPDATE Collaborator c
            SET c.acceptedAt = :at, c.accountId = :accountId
            WHERE c.id = :id AND c.acceptedAt IS NULL AND c.revokedAt IS NULL
            """)
    int claim(@Param("id") UUID id, @Param("accountId") UUID accountId, @Param("at") Instant at);
}
