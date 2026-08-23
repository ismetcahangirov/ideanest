package az.ideanest.project.application;

import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.infrastructure.ProfileCampaignRows;
import az.ideanest.user.application.ProfileNotFoundException;
import az.ideanest.user.application.PublicProfile;
import az.ideanest.user.application.PublicProfiles;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The campaigns on somebody's profile — §4.2's created tab (#282), and the cards the
 * backed tab beside it is made of.
 *
 * <p><strong>Two questions in one class, because they are one rule.</strong>
 * {@link #createdBy} serves {@code GET /v1/users/{slug}/projects}; {@link #publiclyVisible}
 * answers the pledge module, which owns {@code GET /v1/users/{slug}/backed} and holds
 * nothing but identifiers out of {@code pledges}. Both must show exactly the nine states
 * §6.1 publishes, and the second exists precisely so that the module asking does not apply
 * its own copy of that list — or, worse, read {@code projects} to build a card.
 * {@code SavedListResponse} names that second failure as an open gap in its own response
 * and refuses to close it that way; this is the shape that closes it.
 *
 * <p><strong>Not a method on {@link PublicProjects}.</strong> That class answers "may a
 * stranger see <em>this</em> campaign" for a caller that already holds one, and every one
 * of its methods is a single lookup. This one is a list keyed on an account, it pages, and
 * its 404 comes from the profile rather than from any campaign — a creator with no
 * campaigns answers an empty list, and a creator with a private profile answers 404 to a
 * request that never named a campaign at all. What the two share is the state set, and they
 * share it as one constant rather than as two.
 *
 * <h2>Whose 404 this is</h2>
 *
 * <p>The public lists refuse before they read anything, by asking
 * {@link PublicProfiles#requireVisible}. §4.2's P-07 withdraws the profile page, its about
 * tab <em>and its archives</em> — {@code ProfileVisibility} says so — so a list that
 * answered 200 with campaigns for an account whose page answers 404 would publish, through
 * a second URL, the thing the first one withholds. Asking the module that owns the setting
 * is also the only way to know: {@code users} is not this module's table.
 *
 * <p>This module therefore depends on {@code user}, which it already did — every campaign
 * has a creator — and never the other way round. The counts §4.2 puts on the profile header
 * are not served for exactly that reason, and {@link PublicProfiles} carries the argument.
 */
@Service
public class ProfileCampaigns {

    /**
     * How many cards a page holds when the client does not say.
     *
     * <p>Twenty, matching {@code ProjectUpdateService} and {@code BackerSignalService}: a
     * profile grid is read on a phone, and a page is what a reader scrolls past before they
     * ask for another.
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * The ceiling, whatever a client asks for.
     *
     * <p>Clamped rather than refused, for the reason every other paged read here gives: a
     * client that asks for a thousand gets fifty and a cursor, which is a working client,
     * where a 400 is a client that stops.
     */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * §6.1's nine public states, by name, for the query.
     *
     * <p>Derived from {@link PublicProjects#VISIBLE} rather than written out. That set is
     * where the rule is argued — why {@code PRELAUNCH} is in and {@code SUSPENDED} is out —
     * and a second literal here would be the copy that falls behind. As names because the
     * comparison happens in SQL against {@code projects.state}, which is text.
     */
    private static final Set<String> VISIBLE_STATES =
            PublicProjects.VISIBLE.stream().map(ProjectState::name).collect(Collectors.toUnmodifiableSet());

    private final ProfileCampaignRows campaigns;
    private final PublicProfiles profiles;

    public ProfileCampaigns(ProfileCampaignRows campaigns, PublicProfiles profiles) {
        this.campaigns = campaigns;
        this.profiles = profiles;
    }

    /**
     * One page of the campaigns this account created — §4.2's created tab.
     *
     * <p>The profile is resolved first and its absence is the whole of the authorisation:
     * see the class comment.
     *
     * <p><strong>An empty page is not a 404.</strong> A creator who has launched nothing
     * has a profile and an empty tab, and answering 404 would make "this person has no
     * campaigns" indistinguishable from "this person has no profile" — which is the
     * distinction P-07 is about.
     *
     * @param cursor the last row of the previous page, or null for the first
     * @param limit what the client asked for, or null. Clamped, never refused
     * @throws ProfileNotFoundException for a slug nobody holds, an account §17.4 has
     *     anonymised, and one whose owner chose {@code PRIVATE} — identically
     */
    @Transactional(readOnly = true)
    public Page createdBy(String slug, ProfileCursor cursor, Integer limit) {
        PublicProfile profile = profiles.requireVisible(slug);
        int pageSize = pageSizeOf(limit);

        // One more than the page, which is how "is there another page" is answered without
        // a second COUNT over the same predicate -- and without the count and the list
        // being able to disagree about a campaign suspended between the two statements.
        List<ProfileCampaign> found = campaigns.createdBy(profile.id(), VISIBLE_STATES, cursor, pageSize + 1);
        return pageOf(found, pageSize);
    }

    /**
     * The cards for campaigns another module holds identifiers for, keeping only the ones a
     * stranger may see.
     *
     * <p><strong>Published for the pledge module, and shaped so that it cannot be misused
     * as an enumeration.</strong> It takes identifiers the caller already has and answers
     * nothing about the ones it drops: a campaign that does not exist, one whose creator has
     * left, and one in a state §6.1 does not publish are all simply absent, so the caller
     * cannot tell them apart and neither can whoever is reading the caller's response.
     *
     * <p><strong>Order is preserved and shortening is expected.</strong> The backed archive
     * is ordered by when each pledge was made, which is an ordering this module knows
     * nothing about, so the caller's sequence is restored rather than invented here. A page
     * of twenty pledges may yield fewer than twenty cards — that is the state filter doing
     * its job, and the cursor still advances by pledges, so the next page is the next
     * twenty pledges rather than a page that tries to backfill and pages twice over the
     * same rows.
     *
     * @param projectIds in the order the caller wants them back. Duplicates collapse
     * @return one card per identifier that resolved to a publicly visible campaign, in the
     *     given order
     */
    @Transactional(readOnly = true)
    public List<ProfileCampaign> publiclyVisible(List<UUID> projectIds) {
        return inGivenOrder(projectIds, campaigns.publiclyVisible(projectIds, VISIBLE_STATES));
    }

    /**
     * The same, in whatever state the campaign is in.
     *
     * <p>For a backer reading their own pledges. {@code ProjectSummaries} makes the
     * argument in full and it applies unchanged: the reader is already party to the
     * campaign, and the pledges most in need of a name beside them are the ones on a
     * campaign that has stopped being public. A state filter would blank exactly those.
     *
     * <p><strong>Never reachable without an account.</strong> The one endpoint that calls
     * it is {@code GET /v1/me/pledges}, which serves the caller their own rows; a public
     * surface that wanted a card asks {@link #publiclyVisible} instead, and the two are
     * separate methods rather than one with a flag so that the choice is made at the call
     * site and is visible there.
     */
    @Transactional(readOnly = true)
    public List<ProfileCampaign> ofAnyState(List<UUID> projectIds) {
        return inGivenOrder(projectIds, campaigns.ofAnyState(projectIds));
    }

    /**
     * The caller's order, restored.
     *
     * <p>A map rather than a sort, because the ordering key is not on the row: it is the
     * caller's list, and a comparator would need an index lookup per comparison.
     */
    private static List<ProfileCampaign> inGivenOrder(
            Collection<UUID> projectIds, List<ProfileCampaign> found) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProfileCampaign> byId = new LinkedHashMap<>();
        for (ProfileCampaign campaign : found) {
            byId.put(campaign.id(), campaign);
        }
        return projectIds.stream()
                .distinct()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * A page, and the cursor for the next one.
     *
     * <p>The extra row fetched above is dropped here rather than served: it exists only to
     * answer whether there is more, and returning it would make every page one longer than
     * the size the client asked for.
     */
    private static Page pageOf(List<ProfileCampaign> found, int pageSize) {
        if (found.size() <= pageSize) {
            // No further page. Null rather than a cursor that would return nothing, so a
            // client tests one thing and stops.
            return new Page(found, null);
        }
        List<ProfileCampaign> page = found.subList(0, pageSize);
        ProfileCampaign last = page.get(pageSize - 1);
        return new Page(page, new ProfileCursor(last.createdAt(), last.id()));
    }

    private static int pageSizeOf(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    /**
     * One page of a profile's campaigns.
     *
     * @param campaigns the cards, newest first
     * @param next where the following page starts, or null when this is the last. Null
     *     rather than absent or empty, for {@code SavedListResponse}'s reason: a client
     *     tests one thing, and a three-way distinction between null, missing and {@code ""}
     *     is what gets handled two ways in two clients
     */
    public record Page(List<ProfileCampaign> campaigns, ProfileCursor next) {

        public Page {
            campaigns = List.copyOf(campaigns);
        }
    }
}
