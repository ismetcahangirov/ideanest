package az.ideanest.pledge.application;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.infrastructure.BackerListRepository;
import az.ideanest.reward.application.RewardTitles;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import az.ideanest.shared.money.CurrencyMismatchException;
import az.ideanest.shared.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.7's CD-10, CD-07 and CD-08: the campaign team's view of who backed it.
 *
 * <h2>Who may read it</h2>
 *
 * <p>{@link ProjectCapability#VIEW_FINANCES}, asked through {@link ProjectAuthorisation},
 * which is the same check the dashboard, the analytics trend and the referral report make
 * — §3.1 grants "view the backer report" alongside them and the capability's own comment
 * names it. A stranger gets a 404 for the reason those endpoints give: a campaign's
 * takings are competitive information and a 403 would confirm the campaign exists.
 *
 * <p><strong>This is the one dashboard read that returns personal data</strong>, which is
 * why the capability matters more here than anywhere else it is used: a collaborator
 * granted {@code EDIT_REWARDS} can shape what a campaign offers and still cannot read one
 * backer's email address.
 *
 * <h2>Why the page and the splits are separate reads</h2>
 *
 * <p>The splits (CD-07, CD-08) are a property of the campaign; the page is a property of
 * whatever the creator last typed. Folding them into one response would recompute two
 * grouped aggregates on every keystroke of a search — and, worse, would make a chart move
 * when a filter changed, which reads as the campaign changing rather than the question.
 */
@Service
public class BackerReportService {

    private final ProjectAuthorisation projects;
    private final BackerListRepository backers;
    private final RewardTitles rewards;
    private final PledgeProperties properties;

    public BackerReportService(
            ProjectAuthorisation projects,
            BackerListRepository backers,
            RewardTitles rewards,
            PledgeProperties properties) {

        this.projects = projects;
        this.backers = backers;
        this.rewards = rewards;
        this.properties = properties;
    }

    /**
     * One page of the report.
     *
     * @param filter which backers, never null — {@link BackerFilter#ANY} is the whole
     *     campaign
     * @param cursor the previous page's last pledge, or null for the first page. An unknown
     *     cursor answers with an empty page; {@code BackerListRepository} says why that is
     *     the only answer that cannot leak
     * @param size how many rows, or null for the configured default. Clamped to the
     *     configured maximum rather than refused: a client asking for a thousand rows wants
     *     as many as it can have, and a 400 there would be pedantry
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign that
     *     does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException for a
     *     collaborator whose grant does not include VIEW_FINANCES
     */
    @Transactional(readOnly = true)
    public BackerPage page(UUID projectId, UUID accountId, BackerFilter filter, UUID cursor, Integer size) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        int limit = pageSize(size);
        // One more than the page, so that "is there another page" is answered by the query
        // rather than by comparing the count to the offset — which is the version that
        // shows a next-page control on a page that turns out to be empty.
        List<BackerPage.Backer> rows = new ArrayList<>(backers.page(projectId, filter, cursor, limit + 1));
        boolean more = rows.size() > limit;
        if (more) {
            rows.remove(rows.size() - 1);
        }
        if (rows.isEmpty()) {
            // Still counted: a filter that matches nothing on this page may match plenty on
            // the campaign, and a cursor past the end is the ordinary way to reach here.
            return new BackerPage(List.of(), null, backers.count(projectId, filter), null);
        }

        List<BackerPage.Backer> named = named(rows, titlesOf(projectId, rows));
        return new BackerPage(
                named,
                more ? named.get(named.size() - 1).pledgeId() : null,
                backers.count(projectId, filter),
                named.get(0).amount().currency());
    }

    /**
     * The campaign's tier titles, or nothing when the page needs none.
     *
     * <p>Skipped entirely for a page of pledges that all took no reward — §4.5's PL-02 — so
     * that a campaign offering nothing but support does not pay for a lookup on every page.
     */
    private Map<UUID, RewardTitles.RewardTitle> titlesOf(UUID projectId, List<BackerPage.Backer> rows) {
        boolean anyTier = rows.stream().anyMatch(backer -> backer.rewardTierId() != null);
        return anyTier ? rewards.titlesOf(projectId) : Map.of();
    }

    /**
     * The same rows with their tier titles attached.
     *
     * <p>A tier the campaign no longer has leaves the title absent rather than substituting
     * one: the pledge names a tier that was removed, and "no reward" would be a different
     * and false statement about what that backer chose.
     */
    private static List<BackerPage.Backer> named(
            List<BackerPage.Backer> rows, Map<UUID, RewardTitles.RewardTitle> titles) {

        List<BackerPage.Backer> named = new ArrayList<>(rows.size());
        for (BackerPage.Backer backer : rows) {
            RewardTitles.RewardTitle tier = backer.rewardTierId() == null ? null : titles.get(backer.rewardTierId());
            named.add(new BackerPage.Backer(
                    backer.pledgeId(),
                    backer.name(),
                    backer.email(),
                    backer.anonymous(),
                    backer.rewardTierId(),
                    tier == null ? null : tier.title(),
                    backer.amount(),
                    backer.state(),
                    backer.country(),
                    backer.backedAt()));
        }
        return named;
    }

    /**
     * Every backer the filter matches, up to {@code cap + 1} of them, for the export.
     *
     * <p><strong>One more than the cap on purpose</strong>: a list of exactly {@code cap}
     * rows cannot say whether it is the whole answer, and the export has to report that it
     * was cut short. The caller trims and compares.
     *
     * <p>Authorised here rather than by the caller, so that the two ways out of this class
     * — a page and a file — cannot come to disagree about who may read a campaign's
     * backers.
     *
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign that
     *     does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException without
     *     VIEW_FINANCES
     */
    @Transactional(readOnly = true)
    public List<BackerPage.Backer> matching(UUID projectId, UUID accountId, BackerFilter filter, int cap) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        List<BackerPage.Backer> rows = backers.all(projectId, filter, cap);
        return rows.isEmpty() ? rows : named(rows, titlesOf(projectId, rows));
    }

    /**
     * CD-07 and CD-08: what the campaign sold and where it is going.
     *
     * @throws CurrencyMismatchException when a campaign somehow holds reported pledges in
     *     two currencies. Deliberately not a 400 and deliberately not caught, for
     *     {@code ProjectAnalyticsService}'s reason: there is no answer that would be true,
     *     and one of the two reported quietly is a number somebody would act on. §7.3 says
     *     it cannot happen
     */
    @Transactional(readOnly = true)
    public BackerBreakdown breakdown(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        BackerListRepository.Totals totals = backers.totals(projectId);
        if (totals == null || totals.isEmpty()) {
            return BackerBreakdown.empty();
        }
        if (totals.currencies() > 1) {
            throw new CurrencyMismatchException(totals.currency(), totals.otherCurrency());
        }

        String currency = totals.currency();
        return new BackerBreakdown(
                currency,
                totals.backerCount(),
                Money.of(totals.amount(), currency),
                rewardSlices(projectId, currency),
                backers.countrySlices(projectId, currency));
    }

    /**
     * CD-07's slices: the totals from this module's table, the names from the reward
     * module's.
     *
     * <p>The order is the query's — by what each tier took — and is not re-sorted after the
     * titles arrive. A tier the campaign has since removed keeps its row with an absent
     * title and an absent price: the pledges are real and dropping them would make the
     * slices stop adding up to the total above them.
     */
    private List<BackerBreakdown.RewardSlice> rewardSlices(UUID projectId, String currency) {
        List<BackerListRepository.RewardTotal> totals = backers.rewardTotals(projectId);
        if (totals.isEmpty()) {
            return List.of();
        }

        Map<UUID, RewardTitles.RewardTitle> titles = rewards.titlesOf(projectId);
        List<BackerBreakdown.RewardSlice> slices = new ArrayList<>(totals.size());
        for (BackerListRepository.RewardTotal total : totals) {
            RewardTitles.RewardTitle tier = titles.get(total.rewardTierId());
            slices.add(new BackerBreakdown.RewardSlice(
                    total.rewardTierId(),
                    tier == null ? null : tier.title(),
                    tier == null ? null : tier.price(),
                    total.backerCount(),
                    Money.of(total.amount(), currency)));
        }
        return slices;
    }

    /** The page size, defaulted and clamped. */
    private int pageSize(Integer requested) {
        PledgeProperties.Report report = properties.report();
        if (requested == null || requested < 1) {
            return report.pageSize();
        }
        return Math.min(requested, report.maxPageSize());
    }
}
