package az.ideanest.analytics.infrastructure;

import az.ideanest.analytics.domain.ReferralChannel;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One campaign-day split by where its pledges came from.
 *
 * <p>The channel only, which V27's header argues for: {@code source},
 * {@code campaign} and {@code referrer_code} are free text that arrived in a URL, so
 * their cardinality is whatever anybody chose to put in one — unbounded rows per
 * campaign per day. {@link ReferralChannel} is a closed set of seven, so a day is at
 * most seven rows, and the full source breakdown stays at read time in
 * {@code GET /referrers}, where it is already folded past a limit.
 *
 * @param channel the closed vocabulary, parsed here rather than passed on as text so
 *     that a value the enum does not know is a failure at the boundary instead of a
 *     string rendered into somebody's dashboard
 */
public record DailyChannelRow(LocalDate day, ReferralChannel channel, long pledgeCount, BigDecimal amount) {
}
