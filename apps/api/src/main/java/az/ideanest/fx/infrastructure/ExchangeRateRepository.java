package az.ideanest.fx.infrastructure;

import az.ideanest.fx.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Rates, by the two questions asked of them — issue #327.
 *
 * <p>The newest for a pair, which is every read the display makes; and whether one day's
 * publication is already stored, which is what makes the hourly refresh idempotent.
 *
 * <p>There is deliberately no "every rate" read. The table is a history and it grows by
 * forty rows a day; a list of all of it is a report nobody has asked for, and the one caller
 * that would want a range — a future audit screen reconstructing what a pledge was shown —
 * would want a range rather than the lot.
 */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /**
     * The most recently published rate for a pair.
     *
     * <p>Ordered by {@code publishedFor} and not by {@code fetchedAt}, which is the whole
     * point: a source re-serving Friday's rates on Sunday writes nothing, and a backfill
     * fetched today for a day last week must not become "the newest".
     *
     * <p>{@code Limit.of(1)} rather than a {@code Pageable}: Spring Data issues a
     * {@code FETCH FIRST 1 ROW} either way, and a page brings a count query with it that
     * nothing here reads.
     */
    @Query(
            """
            select rate from ExchangeRate rate
            where rate.baseCurrency = :base
              and rate.quoteCurrency = :quote
            order by rate.publishedFor desc
            """)
    Optional<ExchangeRate> newest(@Param("base") String base, @Param("quote") String quote, Limit limit);

    /** @see #newest(String, String, Limit) */
    default Optional<ExchangeRate> newest(String base, String quote) {
        return newest(base, quote, Limit.of(1));
    }

    /**
     * Whether a publication is already stored.
     *
     * <p>Eleven of the twelve daily refresh passes answer true here and write nothing. V59's
     * unique index would refuse the insert anyway, and this is what turns that refusal into
     * a decision the job makes rather than an exception it has to catch — a
     * {@code DataIntegrityViolationException} per pass would mark the job's transaction
     * rollback-only and take the rest of the batch with it.
     */
    boolean existsBySourceAndBaseCurrencyAndQuoteCurrencyAndPublishedFor(
            String source, String baseCurrency, String quoteCurrency, LocalDate publishedFor);
}
