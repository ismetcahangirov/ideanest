package az.ideanest.analytics.infrastructure;

import az.ideanest.analytics.domain.ReferralChannel;
import java.math.BigDecimal;

/**
 * One line of §4.7's CD-03 report, as the database computed it.
 *
 * <p>A projection rather than the entities, so that "the value this source brought" is
 * a {@code SUM} in PostgreSQL and never a fold in Java over rows that were loaded in
 * order to be discarded. A successful campaign has as many attributed pledges as it
 * has backers, and the report has twenty lines.
 *
 * <p>A top-level record rather than one nested in the repository, because a JPQL
 * constructor expression names the class in text: a nested type would be
 * {@code …$SourceTotal} in a string, which is a form the query parser is not obliged
 * to accept and which no compiler would check.
 *
 * <p><strong>It carries an amount and a currency and not a {@code Money}</strong>, for
 * {@code MoneyAmountConverter}'s reason: §7.2 stores the two as separate columns, and
 * a {@code Money} is assembled at the edge — here, by the service, which is also where
 * a mixture of currencies is refused rather than added together.
 *
 * @param pledgeCount how many pledges this source brought. A {@code long} because that
 *     is what {@code COUNT} is, not because anybody expects two billion
 * @param amount the total at the column's scale. Never a double
 */
public record ReferralSourceTotal(
        ReferralChannel channel,
        String source,
        String campaign,
        String referrerCode,
        long pledgeCount,
        BigDecimal amount,
        String currency) {
}
