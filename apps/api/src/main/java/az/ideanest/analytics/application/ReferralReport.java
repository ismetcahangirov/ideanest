package az.ideanest.analytics.application;

import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.util.List;

/**
 * §4.7's CD-03: where a campaign's pledges came from, with volume, value, and share.
 *
 * <p><strong>Nothing here names a backer</strong>, and there is nowhere for one to be
 * named: {@code referral_attributions} has no such column, so this projection cannot
 * omit it by accident or include it by review. §4.5's PL-12 spends a column on keeping
 * a backer anonymous from the public, and a source breakdown fine enough to identify
 * one person would give it back — a campaign with three pledges from a link only that
 * person was sent is the case, and it is why the shape of the answer is counts and
 * totals rather than rows.
 *
 * @param currency what the figures are in, or null when the campaign has no attributed
 *     pledges at all. Null rather than the campaign's currency, because a report with
 *     nothing in it should not imply a total of zero in a currency: there is no total
 * @param pledgeCount how many confirmed pledges are attributed, direct ones included.
 *     The denominator every share is of, stated so that a client does not have to add
 *     the rows up and get a different answer
 * @param value what they came to. Null exactly when {@code currency} is
 * @param sources the sources, most valuable first. Direct pledges appear here as an
 *     ordinary row with the {@link ReferralChannel#DIRECT} channel, because leaving
 *     them out would make every share a share of the part we could explain rather than
 *     of the campaign
 * @param remainder everything past the reporting limit, folded into one line, or null
 *     when nothing was folded. See {@link Remainder}
 */
public record ReferralReport(
        String currency,
        long pledgeCount,
        Money value,
        List<AttributedSource> sources,
        Remainder remainder) {

    public ReferralReport {
        sources = List.copyOf(sources);
    }

    /**
     * One source's contribution.
     *
     * @param channel what kind of place, from the closed set
     * @param source the place, folded — null for a direct visit and for a referral link
     *     that named only its code
     * @param campaign which push, when the link named one
     * @param referrerCode the creator's own share link, when the pledge came through
     *     one. <strong>Visible only to the creator</strong>, which is what the
     *     authorisation on the endpoint is for: a code is not secret, but a list of
     *     every code that ever earned a pledge is a list of who a creator's promoters
     *     are
     * @param pledgeCount how many pledges
     * @param value what they came to. A {@code Money}, so it crosses the API as a
     *     string
     * @param share that value as a percentage of the campaign's attributed total, at
     *     two decimal places. The shares of every row plus the remainder come to
     *     exactly {@code 100.00} — see {@code ReferralShares} for why that is
     *     arithmetic rather than luck
     */
    public record AttributedSource(
            ReferralChannel channel,
            String source,
            String campaign,
            String referrerCode,
            long pledgeCount,
            Money value,
            BigDecimal share) {
    }

    /**
     * The sources past the reporting limit, as one line.
     *
     * <p>A separate field rather than an extra row in {@link #sources()}, because a
     * folded row would need a label and every label it could be given —
     * {@code "other"}, {@code "…"} — is a label a real source might already have. A
     * client renders this as a final line and a person reads it as one; nothing has to
     * guess which of the rows is not a source.
     *
     * <p>Why there is a limit at all: the labels on a visit are free text that arrived
     * in a URL, so the number of distinct sources is bounded by what anybody chose to
     * put in one rather than by anything the platform controls. Without the fold, a
     * creator's dashboard is whatever a script decided to fill it with.
     *
     * @param sourceCount how many sources were folded, so that "and 340 others" can be
     *     said rather than implied
     */
    public record Remainder(long sourceCount, long pledgeCount, Money value, BigDecimal share) {
    }
}
