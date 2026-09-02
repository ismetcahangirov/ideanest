package az.ideanest.admin.application;

import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.project.ProjectSummaries;
import az.ideanest.shared.project.ProjectSummary;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the identifiers on a console screen are called — issue #402.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Every console list renders references to people and campaigns, and until this
 * endpoint not one of the modules answering those lists returned a name. The payout file
 * paid "18844dbc", the ledger credited "Creator 07afbabf", the audit trail recorded that
 * "4ae450ba" suspended somebody, and {@code /admin/staff} told the person reading it that
 * they were signed in as "c5c5493d". Two-person payout approval whose whole point is
 * <em>which two people</em> was rendered so that neither could be named.
 *
 * <p>The alternative to this was adding a name to each of those responses, one module at a
 * time. That is a change to five published contracts to answer one question, and every one
 * of them would have had to join {@code users} for a field the screen renders and the
 * module has no other use for. One lookup, asked by whichever screen holds identifiers, is
 * the smaller surface — and it is the same shape the platform already uses internally:
 * {@link ProjectSummaries#summariesOf} exists because the notification module had exactly
 * this problem with campaign titles.
 *
 * <h2>Staff, and no capability beyond that</h2>
 *
 * <p>Every screen that needs a name is already behind its own capability, and this adds
 * nothing to what those screens are allowed to know. Requiring, say, {@code VIEW_FINANCE}
 * here would mean the payout queue could name its signers and the ledger could not, for no
 * reason anybody could state — and requiring {@code ADMINISTER_ACCOUNTS} would put the
 * account directory's authority in front of a member of finance reading their own queue.
 *
 * <h2>It is not audited, and that is a considered exception</h2>
 *
 * <p>{@code ConsoleReadService} records every read it serves, and the rule it follows is
 * {@code AuditAction.ACCOUNTS_SEARCHED}'s: a read is recorded when it hands over something
 * about a person that the platform does not otherwise publish — an email address, what
 * somebody paid, what was done to their account. <strong>Nothing here is such a fact.</strong>
 * A display name and a profile slug are what {@code GET /v1/users/{slug}} serves to anybody
 * on the internet, and a campaign's title and path are on its public page.
 *
 * <p>The positive reason not to record it is stronger than the absence of one to record it:
 * this is called once per console screen that holds identifiers, on every render, so
 * auditing it would put several rows per page view into the one table on the platform that
 * has no retention rule and cannot be pruned — burying the rows an investigation is
 * actually looking for. {@code SystemHealthService} declines to record itself for the same
 * two reasons in the same order.
 *
 * <p><strong>The email address is deliberately not here.</strong> {@code AdminUserController}
 * serves that, requires {@code ADMINISTER_ACCOUNTS}, and is audited — and it stays the only
 * way to get one. If a caller could learn an address from this endpoint it would need to be
 * audited, and then the paragraph above would be an argument for not having it at all.
 *
 * <h2>An identifier with nothing behind it is absent, not null</h2>
 *
 * <p>Both underlying lookups already answer that way and this preserves it. A caller that
 * could not tell "no such account" from "an account with no name" would render one as the
 * other, and §17.4 leaves rows behind whose author has been anonymised — so an absent
 * answer is an ordinary thing to find rather than an error, and the screen says
 * {@code shortId} for it exactly as it did before.
 */
@Service
public class ConsoleDirectoryService {

    /**
     * How many identifiers one request may name, counting both lists together.
     *
     * <p><strong>The bound is the request's and not either list's, because the constraint
     * is the transport.</strong> A UUID costs forty-five characters as a query parameter,
     * so a hundred of them is about four and a half kilobytes — comfortably inside the
     * eight-kilobyte header block Tomcat accepts, and a request over that ceiling is
     * refused with an HTML error page before any of this code runs. A limit expressed
     * per list would let two full lists produce exactly that, which is a refusal nobody
     * can act on because it does not carry a code.
     *
     * <p>It is also well above what any screen asks for — a console page holds
     * twenty-five rows and a row references a handful of things — and well below what
     * would make this a way to enumerate the platform's accounts a page at a time.
     *
     * <p>Over the limit is a refusal rather than a silent truncation: a screen that asked
     * about sixty rows and was answered about fifty would render ten identifiers with no
     * name and no reason, which is the defect this endpoint exists to remove.
     */
    public static final int MAX_IDENTIFIERS = 100;

    private final PlatformStaff staff;
    private final UserAccounts accounts;
    private final ProjectSummaries projects;

    public ConsoleDirectoryService(PlatformStaff staff, UserAccounts accounts, ProjectSummaries projects) {
        this.staff = staff;
        this.accounts = accounts;
        this.projects = projects;
    }

    /**
     * Names for the identifiers a screen is holding.
     *
     * @param staffId whoever is asking, from their token
     * @param accountIds the people. Duplicates and unknown identifiers are harmless
     * @param projectIds the campaigns, likewise
     * @throws az.ideanest.staff.application.NotAModeratorException for a caller who is not
     *     platform staff
     * @throws TooManyIdentifiersException when the two lists together are over
     *     {@link #MAX_IDENTIFIERS}
     */
    @Transactional(readOnly = true)
    public ConsoleDirectory lookUp(UUID staffId, Collection<UUID> accountIds, Collection<UUID> projectIds) {
        staff.requireStaff(staffId);

        int asked = size(accountIds) + size(projectIds);
        if (asked > MAX_IDENTIFIERS) {
            throw new TooManyIdentifiersException(asked, MAX_IDENTIFIERS);
        }

        Set<UUID> people = distinct(accountIds);
        Set<UUID> campaigns = distinct(projectIds);

        Map<UUID, UserAccount> found = accounts.findAllById(people);
        List<NamedAccount> named = found.values().stream()
                .map(account -> new NamedAccount(account.id(), account.name(), account.slug()))
                .toList();

        List<NamedProject> titled = projects.summariesOf(campaigns).stream()
                .map(ConsoleDirectoryService::titled)
                .toList();

        return new ConsoleDirectory(named, titled);
    }

    private static NamedProject titled(ProjectSummary summary) {
        // `hasPublicPath` rather than the two slugs: half a path is not a shorter path, it
        // is a link to no route at all, and a console that rendered one would send a
        // moderator to a 404 from the screen asking them to decide something.
        return new NamedProject(
                summary.id(),
                summary.title(),
                summary.hasPublicPath() ? summary.slug() : null,
                summary.hasPublicPath() ? summary.creatorSlug() : null,
                summary.creatorId());
    }

    private static int size(Collection<UUID> ids) {
        return ids == null ? 0 : ids.size();
    }

    private static Set<UUID> distinct(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        // A screen holding twenty rows about the same creator asks about that creator
        // twenty times, and the query should not. Deduplicated after the ceiling rather
        // than before it, so that the refusal counts what was sent — a caller told its
        // request was too large should be able to see that from the request.
        return Set.copyOf(ids);
    }

    /**
     * What the platform is prepared to say about a person, to somebody who works here.
     *
     * @param name the display name, as they last saved it. Never blank — {@code name} is
     *     {@code NOT NULL} and registration requires one
     * @param slug their half of a public profile path, which is what makes the name a link
     */
    public record NamedAccount(UUID id, String name, String slug) {
    }

    /**
     * The same about a campaign, in whatever state it is in.
     *
     * @param slug null together with {@link #creatorSlug()} when the campaign has no public
     *     path. See {@link ProjectSummary#hasPublicPath()}
     * @param creatorId whose campaign it is, so that a screen holding a campaign can name
     *     its creator from the same answer rather than asking twice
     */
    public record NamedProject(UUID id, String title, String slug, String creatorSlug, UUID creatorId) {
    }

    /** Everything one request asked about, in no promised order. */
    public record ConsoleDirectory(List<NamedAccount> accounts, List<NamedProject> projects) {
    }
}
