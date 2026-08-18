package az.ideanest.analytics.infrastructure;

import az.ideanest.analytics.domain.ReferralAttribution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Attributed pledges: the write path's one question, and the report's.
 */
public interface ReferralAttributionRepository extends JpaRepository<ReferralAttribution, UUID> {

    /**
     * Whether this pledge has already been attributed.
     *
     * <p>How the listener tolerates redelivery, which {@code OutboxMessage} states as a
     * contract rather than a caveat. It is the cheap half of the answer:
     * {@code referral_attributions_pledge_key} is the half that stays true under
     * concurrency, and this exists so that the ordinary case — the same event arriving
     * twice because a relay's transaction rolled back after it dispatched — costs a
     * lookup rather than a failed insert that poisons the dispatch transaction.
     */
    boolean existsByPledgeId(UUID pledgeId);

    /**
     * §4.7's CD-03: this campaign's attributed pledges, grouped by where they came
     * from.
     *
     * <p>Aggregated in the database rather than in Java. The alternative is loading one
     * row per attributed pledge — every pledge a successful campaign ever took — in
     * order to produce twenty lines, and it gets worse exactly as the campaign gets
     * more successful.
     *
     * <p><strong>The currency is a grouping column, not an assumption.</strong> Every
     * pledge on a campaign is in the campaign's currency today, and §21.2 says a
     * conversion rate is never the basis of a collection, so in practice this returns
     * one currency. Grouping by it anyway means a campaign that somehow held two
     * produces two rows and is refused by {@code Money} at the sum, rather than
     * producing one number that is the addition of two different kinds of thing.
     *
     * <p>Ordered by value, which is the order §4.7 asks for by calling them "top
     * sources": a creator deciding where to spend next is asking which source brought
     * the most money, not which brought the most people. Ties fall to the count and
     * then to the labels, so the ordering is total and the report does not shuffle
     * between two reads of the same data.
     */
    @Query(
            """
            SELECT new az.ideanest.analytics.infrastructure.ReferralSourceTotal(
                       a.channel, a.source, a.campaign, a.referrerCode,
                       COUNT(a), SUM(a.amount), a.currency)
              FROM ReferralAttribution a
             WHERE a.projectId = :projectId
             GROUP BY a.channel, a.source, a.campaign, a.referrerCode, a.currency
             ORDER BY SUM(a.amount) DESC, COUNT(a) DESC,
                      a.channel ASC, a.source ASC, a.campaign ASC, a.referrerCode ASC
            """)
    List<ReferralSourceTotal> report(@Param("projectId") UUID projectId);
}
