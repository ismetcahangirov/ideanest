package az.ideanest.analytics.application;

import az.ideanest.analytics.AnalyticsProperties;
import az.ideanest.analytics.domain.ReferralShares;
import az.ideanest.analytics.infrastructure.ReferralAttributionRepository;
import az.ideanest.analytics.infrastructure.ReferralSourceTotal;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.7's CD-03, read by the creator.
 *
 * <h2>Who may read it</h2>
 *
 * <p>The project module decides, as it does for every other question about who may act
 * on a campaign, and this asks it through {@link ProjectAuthorisation} for
 * {@link ProjectCapability#VIEW_FINANCES} — the creator, or a collaborator the creator
 * granted that one capability. Nothing comes back; only the refusal matters.
 *
 * <p><strong>It used to be the coarse "may this account edit this campaign at
 * all".</strong> Not because that was the right authority — the report says what a
 * campaign has raised and which channels brought it, which is exactly what
 * {@code VIEW_FINANCES} is for — but because the deciding enum lives in
 * {@code project.domain} and {@code ModuleBoundaryTests} rightly forbids naming it from
 * here. #236 published the vocabulary as {@link ProjectCapability}, and the check
 * became the one this module always wanted. A collaborator granted only
 * {@code EDIT_REWARDS} could read this report until it did.
 *
 * <p>A stranger gets {@code ProjectNotFoundException} and a 404, which is
 * {@code ProjectAccess}' standing answer and is the right one here for an extra
 * reason: which sources brought a campaign its money is competitive information, and
 * a 403 would confirm the campaign exists to somebody enumerating identifiers.
 *
 * <h2>Where the arithmetic happens</h2>
 *
 * <p>The counting and the summing are PostgreSQL's, in one grouped query. The share is
 * {@code ReferralShares}, in integer units, so the rows add up to exactly one hundred.
 * Nothing between the two is a {@code double}.
 */
@Service
public class ReferralReportService {

    private final ProjectAuthorisation projects;
    private final ReferralAttributionRepository attributions;
    private final AnalyticsProperties properties;

    public ReferralReportService(
            ProjectAuthorisation projects,
            ReferralAttributionRepository attributions,
            AnalyticsProperties properties) {

        this.projects = projects;
        this.attributions = attributions;
        this.properties = properties;
    }

    /**
     * {@code GET /v1/projects/{id}/referrers}.
     *
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException for a
     *     collaborator who holds a grant that does not include
     *     {@link ProjectCapability#VIEW_FINANCES}
     * @throws az.ideanest.shared.money.CurrencyMismatchException if a campaign somehow
     *     holds attributions in two currencies. Deliberately not caught: there is no
     *     total that would be true, and a report that quietly reported one of the two
     *     would be worse than one that failed
     */
    @Transactional(readOnly = true)
    public ReferralReport of(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        List<ReferralSourceTotal> rows = attributions.report(projectId);
        if (rows.isEmpty()) {
            // No currency and no total, rather than zero in the campaign's currency.
            // "This campaign has taken nothing yet" and "this campaign has taken zero"
            // are different sentences, and only the first one is true.
            return new ReferralReport(null, 0, null, List.of(), null);
        }

        String currency = rows.get(0).currency();
        int limit = properties.referral().maxSources();
        int named = Math.min(rows.size(), limit);

        // The values every share is computed over: the named sources, and — when there
        // are more than the report may name — the folded remainder as one more part.
        // Computed together so that the shares add up to a hundred across the whole
        // answer rather than across the part that fitted.
        List<Money> values = new ArrayList<>(named + 1);
        for (ReferralSourceTotal row : rows.subList(0, named)) {
            values.add(Money.of(row.amount(), row.currency()));
        }
        List<ReferralSourceTotal> folded = rows.subList(named, rows.size());
        if (!folded.isEmpty()) {
            values.add(sum(currency, folded));
        }

        // Refuses a second currency here rather than reporting a number that is the
        // addition of two different kinds of thing.
        List<BigDecimal> shares = ReferralShares.of(values);
        Money total = Money.sum(currency, values);

        List<ReferralReport.AttributedSource> sources = new ArrayList<>(named);
        long pledges = 0;
        for (int row = 0; row < named; row++) {
            ReferralSourceTotal line = rows.get(row);
            pledges += line.pledgeCount();
            sources.add(new ReferralReport.AttributedSource(
                    line.channel(),
                    line.source(),
                    line.campaign(),
                    line.referrerCode(),
                    line.pledgeCount(),
                    values.get(row),
                    shares.get(row)));
        }

        ReferralReport.Remainder remainder = null;
        if (!folded.isEmpty()) {
            long foldedPledges = folded.stream().mapToLong(ReferralSourceTotal::pledgeCount).sum();
            pledges += foldedPledges;
            remainder = new ReferralReport.Remainder(
                    folded.size(), foldedPledges, values.get(named), shares.get(named));
        }

        return new ReferralReport(currency, pledges, total, sources, remainder);
    }

    /** The folded sources' total, added through {@code Money} so a mixture is refused. */
    private static Money sum(String currency, List<ReferralSourceTotal> rows) {
        return Money.sum(currency, rows.stream().map(row -> Money.of(row.amount(), row.currency())).toList());
    }
}
