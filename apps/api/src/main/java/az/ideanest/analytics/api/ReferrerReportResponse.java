package az.ideanest.analytics.api;

import az.ideanest.analytics.application.ReferralReport;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * §4.7's CD-03 on the wire: "top sources with pledge count, value, and share".
 *
 * <p>Money is a {@link Money}, so §10.3's rule applies without this file having to
 * mention it: an amount crosses the API as a string inside
 * {@code {"amount", "currency"}}, never as a JSON number. The share is a
 * {@link BigDecimal} at two decimal places and is serialised as a JSON number, which
 * is the one place that is safe — a percentage is not money, nobody charges it, and
 * two decimal places of percent survives a double exactly.
 *
 * <p><strong>There is no backer anywhere in this body</strong>, and there is no field
 * that could hold one; see {@code ReferralReport}.
 *
 * @param currency what the figures are in, absent when the campaign has no attributed
 *     pledges
 * @param pledgeCount the denominator every share is of, stated so that a client is not
 *     left adding the rows up
 * @param value what those pledges came to, absent when there are none
 * @param sources most valuable first. Direct pledges are an ordinary row with a
 *     {@code DIRECT} channel, so a share is a share of the campaign and not of the
 *     part that could be explained
 * @param remainder everything past the reporting limit as one line, absent when
 *     nothing was folded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferrerReportResponse(
        String currency,
        long pledgeCount,
        Money value,
        List<Source> sources,
        Remainder remainder) {

    public static ReferrerReportResponse of(ReferralReport report) {
        return new ReferrerReportResponse(
                report.currency(),
                report.pledgeCount(),
                report.value(),
                report.sources().stream().map(Source::of).toList(),
                report.remainder() == null ? null : Remainder.of(report.remainder()));
    }

    /** One source's contribution. Null labels are omitted rather than sent as nulls. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(
            String channel,
            String source,
            String campaign,
            String referrerCode,
            long pledgeCount,
            Money value,
            BigDecimal share) {

        static Source of(ReferralReport.AttributedSource attributed) {
            return new Source(
                    attributed.channel().name(),
                    attributed.source(),
                    attributed.campaign(),
                    attributed.referrerCode(),
                    attributed.pledgeCount(),
                    attributed.value(),
                    attributed.share());
        }
    }

    /**
     * The sources past the reporting limit, folded.
     *
     * <p>A field of its own rather than a row in {@link #sources()}, so that a client
     * never has to work out which row is not a source. {@code ReferralReport.Remainder}
     * has the argument.
     */
    public record Remainder(long sourceCount, long pledgeCount, Money value, BigDecimal share) {

        static Remainder of(ReferralReport.Remainder remainder) {
            return new Remainder(
                    remainder.sourceCount(), remainder.pledgeCount(), remainder.value(), remainder.share());
        }
    }
}
