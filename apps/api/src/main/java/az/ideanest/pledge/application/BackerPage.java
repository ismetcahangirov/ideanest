package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §4.7's CD-10: one page of a campaign's backers, as the creator's report shows them.
 *
 * <h2>This body names people, and that is the point of the capability</h2>
 *
 * <p>Everything else this module publishes about backers is an aggregate —
 * {@code PublicBackers} counts them and says at length why it will not list them, because
 * whether a campaign publishes who backed it is #209 and carries
 * {@code status: needs-decision}. <strong>This is a different question with a different
 * answer.</strong> The creator already owes these people a reward; they cannot address a
 * parcel to a number, and §3.1 grants exactly this — "view the backer report" — to the
 * campaign team. What guards it is {@link az.ideanest.shared.access.ProjectCapability#VIEW_FINANCES}
 * and nothing weaker.
 *
 * <p><strong>{@link Backer#anonymous()} does not hide the name here, and must not.</strong>
 * PL-12 is a promise about the public page: an anonymous backer is not listed among the
 * campaign's supporters where anybody can read it. It was never a promise that the creator
 * would ship to an unnamed address. Withholding the name from the fulfilment report would
 * make the reward undeliverable, so the flag travels with the row instead and the screen
 * says what it means: this person asked not to be named publicly.
 *
 * @param backers newest first, by the instant the campaign gained them
 * @param nextCursor the identifier to send as {@code ?cursor=} for the following page, or
 *     null when this is the last one
 * @param matched how many backers the filter matches in total, not how many are on this
 *     page. A creator saving a segment for a bulk message is entitled to know how many
 *     people it reaches before they send it, and a page count cannot tell them
 * @param currency what every amount is in, or null when the filter matched nothing. Null
 *     rather than the campaign's currency, following {@code ReferralReport}: "nothing
 *     matched" and "zero manat matched" are different sentences
 */
public record BackerPage(List<Backer> backers, UUID nextCursor, long matched, String currency) {

    public BackerPage {
        backers = List.copyOf(backers);
    }

    /** Nothing matched, which is an answer rather than the absence of one. */
    public static BackerPage empty() {
        return new BackerPage(List.of(), null, 0, null);
    }

    /**
     * One backer, as the campaign team sees them.
     *
     * @param pledgeId the row this is about, and the pagination cursor. Not the backer's
     *     account identifier, which is deliberately absent: nothing on this screen acts on
     *     an account, and a report is a poor place to publish one
     * @param name the account's display name. Present even when {@link #anonymous()} — see
     *     the class comment
     * @param email where a survey and a fulfilment question go. §4.8 has no other channel
     *     to a backer, so a report without it is a report the pledge manager cannot use
     * @param rewardTitle the tier's title as it stands today, or null for §4.5's PL-02 —
     *     support with no reward. <strong>Today's title, not the one that was promised</strong>:
     *     a tier renamed after the campaign closed renames itself here too, and the
     *     versioned record of what a backer was promised is #80's, which is not built. Said
     *     here rather than discovered when somebody disputes a reward
     * @param amount the pledge total: base, add-ons, bonus, shipping and tax, which is the
     *     generated {@code total_amount} column and never a sum computed in Java
     * @param country the shipping destination, or null where the pledge named none —
     *     digital rewards, and support with no reward at all
     * @param backedAt when the campaign gained this backer. {@code confirmed_at} where the
     *     confirmation path recorded one, and the row's creation otherwise; V31 says why
     *     the fallback exists rather than a sort on a nullable column
     */
    public record Backer(
            UUID pledgeId,
            String name,
            String email,
            boolean anonymous,
            UUID rewardTierId,
            String rewardTitle,
            Money amount,
            PledgeState state,
            String country,
            Instant backedAt) {
    }
}
