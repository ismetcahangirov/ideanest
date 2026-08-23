package az.ideanest.user.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An account as its public profile page shows it — §4.2, and §10.2's
 * {@code GET /v1/users/{slug}}.
 *
 * <p><strong>Not {@link UserAccount}, and the difference is the audience.</strong> That
 * record is what another module is told about a user in order to <em>act</em>: it carries
 * the address, whether the address was proven, whether the account is closing, whether
 * trust and safety stopped it, and which language the platform writes to it in. Every one
 * of those is either personal data or an operational fact, and none of them belongs on a
 * page served to anybody who types a URL. Reusing it here would mean the profile endpoint
 * is one field-mapping mistake away from publishing an email address, which is precisely
 * the failure {@code UserAccount}'s own comment argues the entity must not be handed out
 * to avoid.
 *
 * <p>So this is the second projection of one row, deliberately, and the rule that keeps
 * them apart is stated once: a field is here only if §4.2 puts it on the page.
 *
 * <p><strong>The identifier is carried and is never serialised.</strong> It is here
 * because the two archives on the profile page — P-04's backed campaigns and the created
 * ones beside them — are rows the project and pledge modules own, and both are keyed on
 * the account rather than on the slug. Publishing the identifier instead would make a
 * client join on it, and a client that can join on an account identifier is a client that
 * can enumerate accounts; {@code PublicProfileResponse} is where it is dropped.
 *
 * <p><strong>What is deliberately absent is a count of anything.</strong> §4.2's page
 * shows how many campaigns this account created and how many it backed, and neither
 * number can be answered from this module: {@code projects} and {@code pledges} belong to
 * two others and {@code ModuleBoundaryTests} would refuse the dependency. See
 * {@link PublicProfiles} for the whole argument, including why the counts were not moved
 * onto the list endpoints either.
 *
 * @param id the account. Never rendered — see above
 * @param slug the profile's half of its URL, and the only public name for an account
 * @param name what the person calls themselves. Never blank: {@code users.name} is
 *     {@code NOT NULL}, and an anonymised account is {@code "Deleted account"} — which is
 *     a page nobody can reach, because §17.4 also turns the profile off
 * @param avatarUrl null for an account that has not set one, which is most of them
 * @param bio §4.2's about tab, null until somebody writes one
 * @param joinedAt {@code users.created_at}. On the page because "member since" is the one
 *     thing a stranger weighing a campaign can check about its creator without taking the
 *     creator's word for it. It is a date to a day rather than a moment, as far as anybody
 *     reading it is concerned, and it is served as an instant because §10.3 makes every
 *     timestamp in this API one
 * @param websiteUrl §4.2's P-02, null until somebody sets one. Public because that is the
 *     field's entire purpose: a link only its owner could see would be a bookmark
 * @param location one of V16's eighteen places, or null. Public for the reason it is a
 *     foreign key rather than a string — it is the same vocabulary discovery's {@code ?city=}
 *     filter takes, so a client can render the name and link to {@code /discover?city={slug}}
 *     and land on the campaigns that are actually there
 * @param socialLinks §4.2's P-03, in the order their owner put them. Never null: an account
 *     with no links has an empty list, so a client can tell "this person listed none" from
 *     "the key I expected is missing"
 */
public record PublicProfile(
        UUID id,
        String slug,
        String name,
        String avatarUrl,
        String bio,
        Instant joinedAt,
        String websiteUrl,
        ProfileLocation location,
        List<ProfileSocialLink> socialLinks) {

    public PublicProfile {
        socialLinks = socialLinks == null ? List.of() : List.copyOf(socialLinks);
    }
}
