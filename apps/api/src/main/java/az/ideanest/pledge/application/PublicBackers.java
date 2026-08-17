package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.pledge.infrastructure.PublicBackerRepository;
import az.ideanest.project.application.PublicProjects;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who has backed this campaign, for a reader with no relationship to it.
 *
 * <p>§4.5's PL-12 says an anonymous pledge is "hidden from public lists". This is that
 * list, and building it here rather than leaving it to each surface is the point of
 * the issue: the guarantee is a property of the projection, not a discipline each
 * caller has to keep. What comes out of this class either has an identity in it or has
 * nowhere to put one — see {@link PublicBacker}.
 *
 * <p><strong>Why this is not a method on {@code PledgeService}.</strong> The same split
 * {@code PublicRewardCatalogue} makes against {@code RewardService}, for the same
 * reason. That service is the backer's own checkout: every entry point begins by
 * establishing who is calling, and {@code PledgeDetail} carries the amounts, the
 * shipping destination, the idempotency key, and the anonymity flag itself. This one
 * has no caller to establish — a visitor deciding whether to back a campaign has
 * usually not registered — and what stands in for authorisation is the campaign's
 * state. Folding a public read into a class whose every other method starts with an
 * ownership check is how a public read eventually inherits the wrong projection.
 *
 * <h2>Counts and the list come from one place</h2>
 *
 * <p>{@link PublicBacking} carries the campaign's backer count, the per-tier counts of
 * §4.4's Rewards tab, and a page of backers together, because they are one question
 * and answering them in two places is how they come to disagree. In particular nobody
 * should ever compute a count by taking the length of the list: the list is a page and
 * the count is the campaign, and on a campaign small enough to fit in one page the two
 * agree — which is exactly why the mistake survives review and then misreports the
 * launch it matters on.
 *
 * <p><strong>Not {@code projects.backers_count}</strong>, and that is worth stating
 * plainly. The denormalised counter exists (V6) and discovery reads it, but nothing on
 * the platform writes it yet, so today it is zero for every campaign. The count served
 * here is taken from {@code pledges}, which is the ledger and therefore the answer.
 * When something starts maintaining the counter, the two must be reconciled — and the
 * test that reconciles them belongs with whichever issue starts the writing.
 */
@Service
public class PublicBackers {

    /**
     * The states in which a pledge is a public backing of a campaign.
     *
     * <p><strong>Derived from {@link PledgeState#ACTIVE}, minus {@link
     * PledgeState#DRAFT}</strong>, rather than written out. The active set is §7.2's
     * "one pledge per backer per project" and it is the same six states
     * {@code pledges_project_backer_active_key} enforces; restating five of them here
     * would be a second list to keep in step with a state machine that still has
     * transitions to gain (#56, epic #59). Derivation means the only thing this class
     * can get wrong is the one deliberate difference, which is stated once, here.
     *
     * <p><strong>And the difference is DRAFT.</strong> A draft is a reservation with a
     * five-minute life (§4.5's PL-13), not a commitment. Counting one would make the
     * public backer count rise every time somebody opened a checkout and fall again
     * when they wandered off, and — worse — it would publish that a named person is
     * mid-checkout on a campaign, which is a fact about somebody who has not yet
     * decided anything. {@code CHARGE_FAILED} stays in: §5.1 is still retrying it and
     * the backer is still committed.
     */
    public static final Set<PledgeState> COUNTED = counted();

    /** What a page of backers is when the caller did not say. */
    public static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * The most a caller may ask for in one page.
     *
     * <p>A ceiling rather than a cursor, and the honesty is in {@code PublicBackerController}:
     * there is no consumer paging through this yet, so there is no ordering contract to
     * commit to. What the ceiling does buy today is that no request can ask an
     * unbounded question of the pledge table on the platform's most-read surface.
     */
    public static final int MAX_PAGE_SIZE = 100;

    private final PublicBackerRepository backings;
    private final PublicProjects projects;
    private final UserAccounts accounts;

    public PublicBackers(PublicBackerRepository backings, PublicProjects projects, UserAccounts accounts) {
        this.backings = backings;
        this.projects = projects;
        this.accounts = accounts;
    }

    /**
     * This campaign's backers, as a stranger may see them.
     *
     * <p>Three queries and one cross-module read, in that order: the campaign's total,
     * the per-tier totals, the page itself, and then the identities of everybody named
     * on that page. The identities are resolved in one call rather than one per row —
     * a campaign page is the most-read surface on the platform, and an N+1 there is
     * only noticed when there is enough traffic for it to matter.
     *
     * @param limit how many backers to return. Clamped into {@code 1..}{@link
     *     #MAX_PAGE_SIZE} rather than refused, because a limit is a client's hint about
     *     how much it can draw and not an assertion about the campaign — there is
     *     nothing for a caller to correct in {@code ?limit=1000} and answering a
     *     hundred is what they meant
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one whose state is not public, identically. See
     *     {@link PublicProjects}
     */
    @Transactional(readOnly = true)
    public PublicBacking of(UUID projectId, int limit) {
        projects.requireVisible(projectId);

        long backerCount = backings.countBackers(projectId, COUNTED);
        List<RewardTierBackers> byRewardTier = backings.countBackersByRewardTier(projectId, COUNTED);
        List<Pledge> page = backings.findBackings(projectId, COUNTED, PageRequest.of(0, clamped(limit)));

        return new PublicBacking(backerCount, byRewardTier, publicly(page));
    }

    /**
     * The page, with each row turned into whichever of {@link PublicBacker}'s two
     * shapes it is.
     *
     * <p>Identities are looked up only for the rows that could use one. That is a
     * performance detail and it is also the strongest statement of the rule available
     * in code: an anonymous backer's account is never fetched at all, so there is no
     * moment in this method at which their name exists in memory to be leaked by the
     * next change to it.
     */
    private List<PublicBacker> publicly(List<Pledge> page) {
        Set<UUID> named = new LinkedHashSet<>();
        for (Pledge pledge : page) {
            if (!pledge.isAnonymous()) {
                named.add(pledge.getBackerId());
            }
        }

        Map<UUID, UserAccount> identities = accounts.findAllById(named);
        List<PublicBacker> backers = new ArrayList<>(page.size());
        for (Pledge pledge : page) {
            backers.add(PublicBacker.of(pledge, identities.get(pledge.getBackerId())));
        }
        return List.copyOf(backers);
    }

    /** See {@link #of}: a hint, honoured as far as it is sane. */
    private static int clamped(int limit) {
        if (limit < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /** See {@link #COUNTED}. */
    private static Set<PledgeState> counted() {
        EnumSet<PledgeState> states = EnumSet.copyOf(PledgeState.ACTIVE);
        states.remove(PledgeState.DRAFT);
        return Collections.unmodifiableSet(states);
    }

    /**
     * What a campaign publishes about the people who backed it.
     *
     * @param backerCount every counted pledge on the campaign, anonymous ones included.
     *     PL-12 hides who, never how many
     * @param rewardTiers one entry per tier that has at least one backer, in tier order.
     *     These sum to at most {@code backerCount}; the difference is the pledges that
     *     named no tier, which is §4.5's PL-02, support with no reward
     * @param backers a page of them, most recently confirmed first, each either named or
     *     not. Its length is a page size and is not the campaign's backer count — see
     *     the class comment for why that is worth saying out loud
     */
    public record PublicBacking(long backerCount, List<RewardTierBackers> rewardTiers, List<PublicBacker> backers) {

        public PublicBacking {
            rewardTiers = List.copyOf(rewardTiers);
            backers = List.copyOf(backers);
        }
    }
}
