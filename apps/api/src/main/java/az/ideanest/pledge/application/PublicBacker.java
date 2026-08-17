package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.user.application.UserAccount;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One backing of a campaign, as somebody with no relationship to it may see it.
 *
 * <p>§4.5's PL-12 — "anonymous pledging, hidden from public lists" — is the whole
 * reason this type exists, and the shape is the guarantee. It is not a record with a
 * {@code name} that callers are trusted to leave null, and it is not a record with an
 * {@code isAnonymous} flag beside the name it is supposed to suppress. It is two
 * shapes: {@link Named}, which has an identity, and {@link Anonymous}, which has
 * nowhere to put one.
 *
 * <p><strong>Why the shape rather than a check.</strong> A rule spelled
 * {@code if (!pledge.isAnonymous())} at the one place that renders a backer list is
 * correct on the day it is written and stays correct until the second place — the
 * creator's public "our backers" widget, an export, a notification, the mobile
 * client's own projection. Every one of those is a fresh chance to forget, and the
 * failure is silent: nothing throws, a name simply appears where somebody asked for
 * none. Here there is no branch to forget, because an {@link Anonymous} value has no
 * field a name could be read out of. The compiler is what enforces PL-12, and
 * {@code PublicBackerResponse} pattern-matches over the two rather than testing a
 * boolean, so a third variant added later cannot be silently ignored either.
 *
 * <p><strong>Anonymity hides who, never how many.</strong> There is no variant for
 * "not shown at all", and that is deliberate: an anonymous backer is still a backer,
 * still appears in this list, and is still counted by
 * {@link PublicBackers.PublicBacking#backerCount()}. A campaign whose public count
 * excluded the people who asked not to be named would understate itself to everybody,
 * including the creator reading their own page, and would turn a privacy preference
 * into a funding penalty. What PL-12 buys is the absence of a name, and nothing else.
 *
 * <p><strong>What is never here at all.</strong> No amount, on either variant. What
 * one person gave is between them, the creator, and the ledger; a public list that
 * carried it would publish a stranger's spending whether or not they asked to be
 * named. No reward tier either — a tier with a single backer would identify that
 * backer to anyone reading the reward list beside this one, which is exactly the
 * re-identification an anonymity flag is supposed to prevent. The aggregate counts on
 * {@link PublicBackers.PublicBacking} say how many chose each tier without saying who.
 */
public sealed interface PublicBacker {

    /**
     * When this pledge became a public backing, which is when it was confirmed.
     *
     * <p>Present on both variants, because a timestamp names nobody: it is what makes
     * "recent backers" an order rather than an arbitrary one, and the creator — who
     * already knows every backer by name, because they have to ship to them — learns
     * nothing from it they did not have.
     */
    Instant backedAt();

    /**
     * A backer who did not ask to be hidden.
     *
     * <p>Three published facts and no more: the account identifier, the display name,
     * and the profile slug — the same three §4.2's profile is reachable by. The email
     * address is on {@link UserAccount} and is deliberately not carried across into
     * this type; nothing in this module needs it and a projection that held it would
     * be one serialiser away from publishing it.
     *
     * <p>Every field is required. A {@code Named} with a null name is the failure this
     * whole type exists to prevent, arriving by a different door, so the constructor
     * refuses it rather than letting it reach a client as {@code "name": null} — which
     * a client would render as a blank where a person should be.
     */
    record Named(UUID id, String name, String slug, Instant backedAt) implements PublicBacker {

        public Named {
            Objects.requireNonNull(id, "A named backer is an account");
            Objects.requireNonNull(name, "A named backer has a name");
            Objects.requireNonNull(slug, "A named backer has a profile");
            Objects.requireNonNull(backedAt, "A backing happened at a time");
        }
    }

    /**
     * A backer whose identity this projection does not hold.
     *
     * <p>One field, and that is the point: there is no identifier here, so there is
     * nothing for a later change to leak. Not even the account identifier, which would
     * look harmless and would not be — it is the join key to §4.2's public profile, so
     * a client holding it could resolve the name PL-12 exists to withhold.
     *
     * <p><strong>Two different people land here</strong>, and one of them is worth
     * stating because nobody designed for it. The first asked for it:
     * {@code pledges.is_anonymous} is true. The second is a backer whose account has
     * been anonymised — §17.4 keeps the pledge row, because "pledge #123 was made by
     * user X" has to stay true for the ledger, and severs the identity. The user
     * module's finders exclude a deleted account by construction, so there is no name
     * to be had, and the correct rendering of a person whose identity the platform
     * deliberately no longer holds is a backer with no identity. The count still
     * includes them, which is the other half of §17.4: closing an account does not
     * retract the money it pledged.
     */
    record Anonymous(Instant backedAt) implements PublicBacker {

        public Anonymous {
            Objects.requireNonNull(backedAt, "A backing happened at a time");
        }
    }

    /**
     * The one place a pledge becomes a public backer.
     *
     * <p>It takes the {@link Pledge} rather than a boolean, so there is no argument a
     * caller can pass the wrong way round: which variant is produced is read off the
     * row that recorded the backer's choice, in this method, once. Every other way of
     * building one of these is a caller deciding for itself, which is the arrangement
     * this type is here to remove.
     *
     * <p>{@code backedAt} is {@code confirmed_at}, because confirmation is what makes
     * a pledge a backing — see {@link PublicBackers#COUNTED}. It falls back to
     * {@code created_at} rather than throwing if it is somehow absent: no transition
     * that reaches one of those states leaves it unstamped, so this is unreachable
     * today, and the argument {@code PublicRewardCatalogue} makes about a row that
     * cannot exist applies here too — a public read is not the place to discover a
     * broken invariant, and the worst outcome of one is a campaign page that will not
     * render at all. The two instants are at most one reservation window apart.
     *
     * @param identity the backer's account, or null when the user module has none to
     *     give — an anonymised account, per {@link Anonymous}. Not looked up here: this
     *     is called once per row of a page and a lookup inside it would be an N+1 on
     *     the campaign page, which is why {@link PublicBackers} resolves the whole page
     *     in one call first
     */
    static PublicBacker of(Pledge pledge, UserAccount identity) {
        Instant backedAt = pledge.getConfirmedAt() == null ? pledge.getCreatedAt() : pledge.getConfirmedAt();
        if (pledge.isAnonymous() || identity == null) {
            return new Anonymous(backedAt);
        }
        return new Named(identity.id(), identity.name(), identity.slug(), backedAt);
    }
}
