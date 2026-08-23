package az.ideanest.user.infrastructure;

import az.ideanest.user.domain.SocialLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A person's accounts elsewhere — §4.2's P-03.
 *
 * <p>Two operations, because there are only two: read one account's list in order, and
 * remove it. {@code ProfileEditing} rewrites the whole list on every edit — the shape V46
 * argues on the table, and the one {@code survey_questions} already takes for ordered child
 * rows — so there is no update-one-row method here and there should not be: a partial write
 * would have to reconcile positions against rows it did not load.
 *
 * <p>Unlike {@link UserRepository}, nothing here excludes a soft-deleted account. It cannot:
 * these rows carry no {@code deleted_at} of their own, and the one caller that reads them for
 * an account that may be deleted is the anonymisation job, whose whole purpose is to act on
 * one. Every other caller has already loaded a live {@code users} row.
 */
public interface SocialLinkRepository extends JpaRepository<SocialLink, UUID> {

    List<SocialLink> findByUserIdOrderByPositionAsc(UUID userId);

    /**
     * Removes every link this account has.
     *
     * <p>Both the first half of a rewrite and the whole of an erasure. §17.4 requires the
     * second: an anonymised account whose Instagram address survived has not been
     * anonymised, and {@code ON DELETE CASCADE} is no help because {@code users} rows are
     * never hard-deleted — the row survives so the ledger keeps reconciling.
     *
     * <p><strong>A bulk statement rather than a derived delete, and the difference is the
     * flush order.</strong> A derived {@code deleteByUserId} loads the rows and removes the
     * entities, which Hibernate executes at flush <em>after</em> the inserts in the same
     * transaction; the rewrite would then insert a link for a platform the account still has
     * a row for and be refused by {@code user_social_links_account_platform_key} — on every
     * ordinary edit, since an ordinary edit keeps most of the list. Written this way the
     * DELETE runs when it is called.
     *
     * <p>{@code clearAutomatically} is deliberately off. Nothing holds a managed
     * {@link SocialLink} across this call: the rewrite inserts fresh instances afterwards and
     * the erasure has none at all. Clearing would also detach the {@code User} the
     * anonymisation job is midway through overwriting, which is exactly the failure
     * {@code AccountAnonymiser}'s comment about statement ordering describes.
     *
     * @return how many rows went
     */
    @Modifying
    @Query("DELETE FROM SocialLink l WHERE l.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
