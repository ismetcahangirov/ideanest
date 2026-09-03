package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.infrastructure.CampaignDirectoryRow;
import az.ideanest.project.infrastructure.ProjectRepository;
import az.ideanest.project.infrastructure.PublicProjectPages;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import az.ideanest.shared.money.Money;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every campaign on the platform, for the people who operate it.
 *
 * <p><strong>The console could not answer "what campaigns are there".</strong> §4.11's
 * sixteen modules gave staff three ways to reach a campaign — a report somebody filed
 * about it, the submission queue #381 added, and a suspension endpoint that takes an id
 * a member of staff had to already have. All three start from a campaign that has done
 * something. A campaign that is simply live, or simply a draft, or that was approved
 * last week and has been sitting unlaunched since, appeared in none of them, and the
 * only way to look one up was psql.
 *
 * <h2>Why this is not the submission queue with the filter widened</h2>
 *
 * <p>{@link CampaignSubmissionQueue} answers "what is waiting on a moderator": oldest
 * first, keyed on the transition that put each campaign in its state, carrying the note
 * that came with the last decision, and refusing any state that is not one a moderator
 * decides. Widening it would mean an endpoint whose ordering — how long something has
 * waited — is meaningless for the states it had been widened to include, and a queue
 * that stops being a queue is a screen nobody can work from.
 *
 * <p>This is a directory: newest first, every state, with the funding figures a member
 * of staff wants when they are looking at a campaign rather than deciding it.
 *
 * <h2>Who may read it</h2>
 *
 * <p>{@code MODERATE_CONTENT}, the check the submission queue makes. This is a wider
 * read than that one — it includes drafts, which are private working documents their
 * creators have shown nobody — so it cannot be a looser check, and there is no capability
 * between "staff at all" and this one that would fit better.
 */
@Service
public class CampaignDirectory {

    private final ProjectRepository projects;
    private final PublicProjectPages pages;
    private final UserAccounts accounts;
    private final PlatformStaff staff;
    private final ProjectProperties properties;

    public CampaignDirectory(
            ProjectRepository projects,
            PublicProjectPages pages,
            UserAccounts accounts,
            PlatformStaff staff,
            ProjectProperties properties) {

        this.projects = projects;
        this.pages = pages;
        this.accounts = accounts;
        this.staff = staff;
        this.properties = properties;
    }

    /**
     * One page, newest first.
     *
     * @param staffId whoever is signed in
     * @param state the state to narrow to, or null for every campaign. Every value of the
     *     enum is a legitimate filter here, which is the difference from the queue: this
     *     endpoint is not asking what can be decided, so "no campaigns are LIVE" is a fact
     *     about the platform rather than a misuse of the endpoint, and an empty page says
     *     it perfectly well
     * @param after the last campaign of the previous page, or null for the first
     * @param limit already clamped by the controller, which is where a request's shape is
     *     decided
     */
    @Transactional(readOnly = true)
    public CampaignDirectoryPage page(UUID staffId, ProjectState state, UUID after, int limit) {
        staff.requireCapability(staffId, StaffCapability.MODERATE_CONTENT);

        List<CampaignDirectoryRow> rows = read(state, after, limit);
        if (rows.isEmpty()) {
            return new CampaignDirectoryPage(state, List.of(), null);
        }

        Map<UUID, UserAccount> creators = accounts.findAllById(creatorIdsOf(rows));

        // A full page is the only honest signal that there may be more, which is the
        // argument the report queue makes and the submission queue repeats.
        UUID nextCursor = rows.size() < limit ? null : rows.get(rows.size() - 1).getProjectId();
        return new CampaignDirectoryPage(
                state, rows.stream().map(row -> toCampaign(row, creators)).toList(), nextCursor);
    }

    /**
     * One campaign, as its page would read, in whatever state it is in — #399.
     *
     * <h2>Why this exists at all</h2>
     *
     * <p>The submission queue asks a moderator to approve, reject or send back a campaign,
     * and its only link to that campaign pointed at the public page. A campaign in review
     * is not public — that is what being in review means — so the link was a 404 by
     * construction, and the decision was taken on a title, a creator's name and a goal
     * figure. Everything the creator actually wrote was one screen away and unreachable.
     *
     * <h2>The same page, not a summary of it</h2>
     *
     * <p>{@link PublicProjectPages#find(UUID, String)} is the projection the public page
     * is served from, and this reads it unchanged. A moderator decides whether a campaign
     * may be published, so what they must be shown is what publishing it would show;
     * anything narrower would be a second description of the campaign, and the decision
     * would be made against the description rather than the thing.
     *
     * <h2>Every state, and the check that makes that acceptable</h2>
     *
     * <p>{@code MODERATE_CONTENT}, the same capability {@link #page} requires and for the
     * stronger version of its reason. The directory lists drafts; this reads one, and a
     * draft is a private working document its creator has shown nobody. There is no
     * capability between "staff at all" and this one, and the audience for this endpoint
     * is exactly the audience that can already approve or reject the campaign.
     *
     * @param locale one of §21.1's four, already narrowed by {@code Taxonomy.localeFor};
     *     decides what language the category and subcategory come back named in
     * @throws ProjectNotFoundException when there is no such campaign, or its creator is
     *     inside §17.4's deletion grace period — see {@code PublicProjectPages}
     */
    @Transactional(readOnly = true)
    public PublicProjectPage preview(UUID staffId, UUID projectId, String locale) {
        staff.requireCapability(staffId, StaffCapability.MODERATE_CONTENT);

        return pages.find(projectId, locale).orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /** The page size a request gets, clamped rather than refused. */
    public int pageSize(Integer requested) {
        ProjectProperties.Directory limits = properties.directory();
        if (requested == null) {
            return limits.defaultPageSize();
        }
        // Clamped at both ends. A client asking for a thousand is asking for as much as it
        // can have, and a 400 there would only teach it to ask for the maximum.
        return Math.clamp(requested, 1, limits.maxPageSize());
    }

    /** Four queries and no nullable parameters — see {@code ProjectRepository}. */
    private List<CampaignDirectoryRow> read(ProjectState state, UUID after, int limit) {
        if (state == null) {
            return after == null
                    ? projects.findCampaignDirectory(limit)
                    : projects.findCampaignDirectoryAfter(after, limit);
        }
        return after == null
                ? projects.findCampaignDirectoryInState(state.name(), limit)
                : projects.findCampaignDirectoryInStateAfter(state.name(), after, limit);
    }

    /** Distinct, because one creator with three campaigns on the page is one lookup. */
    private static Set<UUID> creatorIdsOf(List<CampaignDirectoryRow> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (CampaignDirectoryRow row : rows) {
            ids.add(row.getCreatorId());
        }
        return ids;
    }

    private static DirectoryCampaign toCampaign(CampaignDirectoryRow row, Map<UUID, UserAccount> creators) {
        UserAccount creator = creators.get(row.getCreatorId());
        return new DirectoryCampaign(
                row.getProjectId(),
                row.getTitle(),
                row.getSlug(),
                ProjectState.valueOf(row.getState()),
                row.getCreatedAt(),
                row.getLaunchedAt(),
                row.getDeadline(),
                Money.orNull(row.getGoalAmount(), row.getCurrency()),
                Money.of(row.getPledgedAmount(), row.getCurrency()),
                row.getBackersCount(),
                row.getCreatorId(),
                creator == null ? null : creator.name(),
                creator == null ? null : creator.slug());
    }
}
