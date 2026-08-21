package az.ideanest.community.application;

import az.ideanest.community.CommunityProperties;
import az.ideanest.community.domain.Follow;
import az.ideanest.community.domain.Save;
import az.ideanest.community.infrastructure.FollowRepository;
import az.ideanest.community.infrastructure.SaveRepository;
import az.ideanest.project.application.PublicProjects;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.9's C-09 and C-10: the two things somebody can say about a campaign without spending
 * anything.
 *
 * <p><strong>One service for both, because they are one feature.</strong> A save and a follow
 * are the same shape — an account, a target, a timestamp, idempotent registration, deletion on
 * withdrawal — and the two screens that use them are the same screen. Splitting them would
 * duplicate every decision below and give the next reader two places to look for the one they
 * want.
 *
 * <h2>Neither of them is authorised, and both of them are checked</h2>
 *
 * <p>There is no capability involved: anybody signed in may save any campaign the public can
 * see, and follow anybody who has an account. What stands in for authorisation is the
 * target's visibility, and it is asked of the module that owns it —
 * {@link PublicProjects#requireVisible} for a campaign, {@link UserAccounts#findBySlug} for an
 * account. Both answer a caller who may not see the target with the same 404 they give one
 * asking about something that does not exist, which is the rule {@code CommentService} states:
 * an endpoint that distinguished the two would report on what other people are preparing.
 *
 * <p><strong>Saving is not a read of the campaign.</strong> It is worth being explicit, because
 * the visibility check makes it look like one: what a save proves is that this account asked to
 * be shown this campaign again, and §6.1's nine published states are the whole of what may be
 * saved. A campaign that is later suspended stays saved — removing the row would be deciding
 * on somebody's behalf that they are no longer interested, and the campaign coming back would
 * have no way to restore it.
 *
 * <h2>Idempotency is the database's, and the answer never says which happened</h2>
 *
 * <p>Both registrations are {@code INSERT ... ON CONFLICT DO NOTHING} — {@code V32} argues it,
 * and it is {@code PrelaunchService}'s argument before that: a read-then-write check loses the
 * race between two taps and produces two rows where the unique constraint is the only thing
 * that can decide.
 *
 * <p>The endpoints return the resulting state rather than what this call did, so pressing the
 * button twice is indistinguishable from pressing it once. That is a product decision as much
 * as a safety one: the control is a toggle, and a toggle that reported "already on" would be
 * asking the reader to care about a race they cannot see.
 *
 * <h2>No events, and that is #245's boundary rather than an omission</h2>
 *
 * <p>Nothing here records an outbox event. Saving a campaign is not something anybody else is
 * told about — the creator gets no notification, by design, because a save is private interest
 * and turning it into a message would make it public. What these rows are <em>for</em> is being
 * enumerated later, when something happens to the campaign, and that is
 * {@code CommunityProjectAudiences}.
 */
@Service
public class BackerSignalService {

    private static final Logger log = LoggerFactory.getLogger(BackerSignalService.class);

    private final SaveRepository saves;
    private final FollowRepository follows;
    private final PublicProjects publicProjects;
    private final ProjectSummaries campaigns;
    private final UserAccounts users;
    private final CommunityProperties properties;

    public BackerSignalService(
            SaveRepository saves,
            FollowRepository follows,
            PublicProjects publicProjects,
            ProjectSummaries campaigns,
            UserAccounts users,
            CommunityProperties properties) {

        this.saves = saves;
        this.follows = follows;
        this.publicProjects = publicProjects;
        this.campaigns = campaigns;
        this.users = users;
        this.properties = properties;
    }

    /**
     * Saves a campaign for this account, or confirms that it is already saved.
     *
     * @param accountId the authenticated caller, never a value from a request body
     * @throws az.ideanest.project.application.ProjectNotFoundException when there is no such
     *     campaign, and when it is not one the public may see — deliberately the same answer
     */
    @Transactional
    public void save(UUID projectId, UUID accountId) {
        publicProjects.requireVisible(projectId);
        int created = saves.insertIfAbsent(Identifiers.newIdentifier(), projectId, accountId);
        log.debug("Campaign {} {} by {}.", projectId, created == 1 ? "saved" : "was already saved", accountId);
    }

    /**
     * Un-saves a campaign. Does nothing when it was not saved, which is the state the caller
     * asked for.
     *
     * <p><strong>No visibility check.</strong> Every other method here asks whether the caller
     * may see the campaign first; this one must not, because a campaign that has stopped being
     * public is exactly the one somebody is most likely to want off their list — and refusing
     * would leave a row that only an administrator could remove.
     */
    @Transactional
    public void unsave(UUID projectId, UUID accountId) {
        int removed = saves.delete(projectId, accountId);
        log.debug("Campaign {} {} by {}.", projectId, removed == 1 ? "un-saved" : "was not saved", accountId);
    }

    /** Whether this account has saved this campaign. */
    @Transactional(readOnly = true)
    public boolean hasSaved(UUID projectId, UUID accountId) {
        return saves.existsByProjectIdAndUserId(projectId, accountId);
    }

    /**
     * One page of this account's saved campaigns, newest first — §10.2's
     * {@code GET /v1/me/saved}.
     *
     * <p><strong>What comes back is a summary and not a card</strong>, and the difference is
     * worth stating because a client will want the card. {@code ProjectSummaries} publishes a
     * title and a public path; a campaign card additionally has a cover image, a funding
     * total, a percentage and a deadline, and those live in {@code discovery.domain.ProjectCard}
     * — behind a read that ranks, facets and filters. Assembling one here would mean either
     * this module reading {@code projects} or the discovery module reading {@code saves}, and
     * both are the coupling the ports exist to prevent. <strong>Named gap:</strong> the saved
     * list renders as titles until the discovery module can be asked for cards by identifier,
     * which is its own change.
     *
     * <p>A saved campaign whose row has since been hard deleted simply does not appear.
     * {@code summariesOf} states that contract; the alternative — a hole in the page, or a
     * failure — would be worse on the read where the platform is showing somebody their own
     * list.
     */
    @Transactional(readOnly = true)
    public SavedCampaignPage saved(UUID accountId, SignalCursor cursor, Integer requestedSize) {
        int size = properties.signals().pageSize(requestedSize);

        // One more than the page, so that "there is a next page" is a fact this method knows
        // rather than one the client infers from a full page — the same trick the notification
        // inbox uses, and the reason a cursor is never handed out for a page that ends the list.
        PageRequest limit = PageRequest.ofSize(size + 1);
        List<Save> rows = cursor == null
                ? saves.page(accountId, limit)
                : saves.pageBefore(accountId, cursor.at(), cursor.id(), limit);

        boolean more = rows.size() > size;
        List<Save> page = more ? rows.subList(0, size) : rows;

        Map<UUID, ProjectSummary> summaries = new HashMap<>();
        for (ProjectSummary summary : campaigns.summariesOf(page.stream().map(Save::getProjectId).toList())) {
            summaries.put(summary.id(), summary);
        }

        List<SavedCampaign> items = page.stream()
                .map(row -> SavedCampaign.of(row, summaries.get(row.getProjectId())))
                .filter(Objects::nonNull)
                .toList();

        // The cursor names the last row *read*, not the last row returned. A campaign dropped
        // above because it no longer exists still advances the page, so a hard-deleted campaign
        // sitting on a page boundary cannot make paging stall on it forever.
        Save last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new SavedCampaignPage(items, more && last != null ? new SignalCursor(last.getCreatedAt(), last.getId()) : null);
    }

    /**
     * Follows an account by its public slug — §4.9's C-10.
     *
     * @param followerId the authenticated caller
     * @throws FollowTargetNotFoundException when there is no such account, or it has been
     *     closed
     * @throws CannotFollowYourselfException when the slug resolves to the caller.
     *     {@code follows_is_not_self} would refuse the row anyway; this turns a constraint
     *     violation into a 422 that says what happened
     */
    @Transactional
    public void follow(String creatorSlug, UUID followerId) {
        UUID creatorId = resolve(creatorSlug);
        if (creatorId.equals(followerId)) {
            throw new CannotFollowYourselfException();
        }
        int created = follows.insertIfAbsent(Identifiers.newIdentifier(), creatorId, followerId);
        log.debug("Account {} {} {}.", followerId, created == 1 ? "followed" : "already followed", creatorId);
    }

    /**
     * Unfollows. Does nothing when the caller was not following, which is the state they asked
     * for.
     *
     * <p>The slug still has to resolve, unlike {@link #unsave}: there is no row to remove
     * without an account to remove it for, and a closed account's followers are unreachable
     * either way. A caller who wants out of a list belonging to somebody who has left is
     * asking about rows that no longer notify anybody.
     */
    @Transactional
    public void unfollow(String creatorSlug, UUID followerId) {
        UUID creatorId = resolve(creatorSlug);
        int removed = follows.delete(creatorId, followerId);
        log.debug("Account {} {} {}.", followerId, removed == 1 ? "unfollowed" : "was not following", creatorId);
    }

    /** Whether this account follows that one. */
    @Transactional(readOnly = true)
    public boolean isFollowing(String creatorSlug, UUID followerId) {
        return follows.existsByCreatorIdAndFollowerId(resolve(creatorSlug), followerId);
    }

    /**
     * One page of the accounts this account follows, newest first.
     *
     * <p>The mirror of {@link #saved}, and the same bound applies: what comes back is who,
     * from this module's rows, plus whatever the user module publishes about them.
     */
    @Transactional(readOnly = true)
    public FollowedCreatorPage following(UUID followerId, SignalCursor cursor, Integer requestedSize) {
        int size = properties.signals().pageSize(requestedSize);

        PageRequest limit = PageRequest.ofSize(size + 1);
        List<Follow> rows = cursor == null
                ? follows.page(followerId, limit)
                : follows.pageBefore(followerId, cursor.at(), cursor.id(), limit);

        boolean more = rows.size() > size;
        List<Follow> page = more ? rows.subList(0, size) : rows;

        // One lookup per row, unlike the saved list's single batch, and the asymmetry is
        // deliberate: `UserAccounts` publishes no batch read, and adding one to the user
        // module for a page of twenty would be a change to somebody else's published surface
        // for a screen nobody has asked for yet. Stated here so the next reader knows it was
        // seen rather than missed -- the day this list is opened often, the batch belongs
        // there and not in a join written from this module.
        List<FollowedCreator> items = page.stream()
                .map(row -> users.findById(row.getCreatorId())
                        .map(account -> FollowedCreator.of(row, account))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        Follow last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new FollowedCreatorPage(
                items, more && last != null ? new SignalCursor(last.getCreatedAt(), last.getId()) : null);
    }

    /**
     * The account behind a public slug, or a 404.
     *
     * <p>Through {@code UserAccounts}, which is the user module's application layer and the
     * only part of it this module may name — {@code users} is somebody else's table.
     */
    private UUID resolve(String creatorSlug) {
        return users.findBySlug(creatorSlug)
                .map(UserAccount::id)
                .orElseThrow(() -> new FollowTargetNotFoundException(creatorSlug));
    }
}
