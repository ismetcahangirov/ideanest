package az.ideanest.analytics.infrastructure;

import az.ideanest.analytics.domain.ReferralTouch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Visits, by the three questions asked of them.
 *
 * <p>One per write path and one per read path, and nothing speculative: a derived
 * method nobody calls is a query nobody has looked at the plan for.
 *
 * <p><strong>None of these compares a source in SQL.</strong> A visit's labels are
 * three nullable columns, so "the same place" would be three null-safe comparisons per
 * query — and a null-safe comparison against a bound parameter is exactly the
 * construct that behaves differently between HQL and the SQL it becomes. The
 * comparison is {@code ReferralSource}'s own {@code equals}, applied to a bounded page
 * of rows the index has already narrowed, which is one implementation of "the same
 * place" rather than one per query.
 */
public interface ReferralTouchRepository extends JpaRepository<ReferralTouch, UUID> {

    /**
     * This account's visits to this campaign that could still be evidence.
     *
     * <p>The read the attribution rule is applied to. It narrows by campaign, by
     * account, and by the window — {@code expiresAt > pledgedAt}, which is the far
     * boundary — and leaves the rest to {@code LastNonDirectTouch}, which is where the
     * rule lives and where it can be tested without a database.
     *
     * <p><strong>The window is in the query as well as in the rule</strong>, and that
     * is not a duplicated decision: it is the same decision applied where it is cheap.
     * A visitor who has been reading a campaign for a year has a year of rows, and
     * loading all of them to discard all but the last few is the work
     * {@code referral_touches_attribution_idx} exists to avoid. The rule still applies
     * it, because the rule has to be correct against whatever it is handed.
     *
     * <p>Bounded by the caller, and ordered so that the bound keeps the rows that
     * matter. A visitor with ten thousand visits inside one window is a bot or a
     * misconfigured client; the most recent few decide the answer either way, and
     * reading the rest would be a page of memory spent to reach the same conclusion.
     */
    @Query(
            """
            SELECT t FROM ReferralTouch t
            WHERE t.projectId = :projectId
              AND t.backerId = :backerId
              AND t.occurredAt <= :pledgedAt
              AND t.expiresAt > :pledgedAt
            ORDER BY t.occurredAt DESC, t.id DESC
            """)
    List<ReferralTouch> findEvidence(
            @Param("projectId") UUID projectId,
            @Param("backerId") UUID backerId,
            @Param("pledgedAt") Instant pledgedAt,
            Pageable page);

    /**
     * This visitor's recent visits to this campaign, most recent first.
     *
     * <p>What deduplication is asked against. A reload, a second tab, and a link
     * followed twice in an afternoon are one fact; recorded three times they do not
     * change the report — the rule takes one touch — but they do turn the evidence
     * table into a hit counter, and it is the table with no retention job.
     *
     * <p>{@code since} is the repeat-visit interval, so the page is what happened
     * inside one session. The caller compares the sources: two visits from genuinely
     * different places inside one session are two facts, and collapsing them would
     * attribute the pledge to whichever arrived first.
     */
    @Query(
            """
            SELECT t FROM ReferralTouch t
            WHERE t.projectId = :projectId
              AND t.visitorHash = :visitorHash
              AND t.occurredAt >= :since
            ORDER BY t.occurredAt DESC, t.id DESC
            """)
    List<ReferralTouch> findRecent(
            @Param("projectId") UUID projectId,
            @Param("visitorHash") byte[] visitorHash,
            @Param("since") Instant since,
            Pageable page);

    /**
     * Every unclaimed visit this visitor made to this campaign that is still open.
     *
     * <p>The other half of attribution, and the half without which it would only ever
     * see people who arrived already signed in: a visitor browses anonymously, signs
     * in at checkout, and these are the rows that then become theirs.
     *
     * <p>Only the unclaimed ones. A touch already attached to an account is left
     * alone, for {@code ReferralTouch#claimedBy}'s reason — a shared device or a
     * forwarded link would otherwise transfer one person's browsing to another's
     * report.
     */
    @Query(
            """
            SELECT t FROM ReferralTouch t
            WHERE t.projectId = :projectId
              AND t.visitorHash = :visitorHash
              AND t.backerId IS NULL
              AND t.expiresAt > :now
            """)
    List<ReferralTouch> findUnclaimed(
            @Param("projectId") UUID projectId, @Param("visitorHash") byte[] visitorHash, @Param("now") Instant now);
}
