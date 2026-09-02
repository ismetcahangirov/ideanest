package az.ideanest.admin.api;

import az.ideanest.admin.application.ConsoleDirectoryService.ConsoleDirectory;
import az.ideanest.admin.application.ConsoleDirectoryService.NamedAccount;
import az.ideanest.admin.application.ConsoleDirectoryService.NamedProject;
import java.util.List;
import java.util.UUID;

/**
 * The console's identifier lookup on the wire — #402.
 *
 * <p>Two lists rather than one map keyed by identifier, and the identifier is repeated
 * inside each row. A JSON object keyed by UUID is awkward to type on the client and
 * impossible to describe in the contract as anything better than "an object with unknown
 * keys"; a list of rows that each name themselves types cleanly and reads the same way
 * every other list response on this prefix does.
 *
 * <p><strong>An identifier that resolved to nothing is simply not in the answer.</strong>
 * The lists are not positional and are not padded with nulls, which is the contract
 * {@code ProjectSummaries} already states and the reason it states it: §17.4 leaves rows
 * behind whose author has been anonymised, so an identifier with nothing behind it is an
 * ordinary thing to find rather than an error, and a null row would be a name the client
 * would have to remember not to render.
 */
final class ConsoleDirectoryResponses {

    private ConsoleDirectoryResponses() {
    }

    static Directory of(ConsoleDirectory directory) {
        return new Directory(
                directory.accounts().stream()
                        .map(ConsoleDirectoryResponses::account)
                        .toList(),
                directory.projects().stream()
                        .map(ConsoleDirectoryResponses::project)
                        .toList());
    }

    private static Account account(NamedAccount named) {
        return new Account(named.id(), named.name(), named.slug());
    }

    private static Project project(NamedProject named) {
        return new Project(named.id(), named.title(), named.slug(), named.creatorSlug(), named.creatorId());
    }

    /**
     * Everything one lookup asked about that exists.
     *
     * @param accounts one row per identifier that named a live account, in no promised order
     * @param projects one row per identifier that named a campaign, in any state
     */
    record Directory(List<Account> accounts, List<Project> projects) {
    }

    /**
     * A person, as somebody who works here may see them named.
     *
     * <p><strong>No email address.</strong> {@code GET /v1/admin/users} serves that, requires
     * {@code ADMINISTER_ACCOUNTS} and is audited; this is neither, and it is only neither
     * because there is nothing here that {@code GET /v1/users/{slug}} does not already serve
     * to the public.
     *
     * @param slug their half of a public profile path, so a console row can be a link
     */
    record Account(UUID id, String name, String slug) {
    }

    /**
     * A campaign, in whatever state it is in.
     *
     * @param slug null together with {@code creatorSlug} when the campaign has no public
     *     path — a campaign in review has one and a half of a path is a link to no route at
     *     all
     * @param creatorId whose it is, so a screen holding a campaign can name the creator from
     *     the same answer instead of asking a second time
     */
    record Project(UUID id, String title, String slug, String creatorSlug, UUID creatorId) {
    }
}
