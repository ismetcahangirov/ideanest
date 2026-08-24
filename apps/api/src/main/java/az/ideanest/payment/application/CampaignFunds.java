package az.ideanest.payment.application;

import az.ideanest.shared.money.Money;

/**
 * What a campaign actually holds — the input to a payout, issue #69.
 *
 * <p><strong>Both numbers, and never their difference alone.</strong> A creator asking why
 * they were paid what they were paid is asking two questions — how much came in, and what
 * came back out — and a single net figure answers neither. V55 stores both on the payout
 * row for the same reason.
 *
 * @param collected the sum of the campaign's settled charges. Read from
 *     {@code transactions} rather than from {@code pledges.amount}, because what can be
 *     paid out is what was taken, and those differ whenever a collection was partial
 * @param refunded what has gone back, including refunds that are requested and not yet
 *     sent. Paying a creator money that is on its way back to a backer is the one mistake
 *     a payout must not make
 */
public record CampaignFunds(Money collected, Money refunded) {

    /** What is left. Never negative — a campaign cannot refund more than it took. */
    public Money net() {
        return collected.minus(refunded);
    }

    /** Nothing was collected. The answer for a campaign with no settled charge. */
    public static CampaignFunds none(String currency) {
        return new CampaignFunds(Money.zero(currency), Money.zero(currency));
    }
}
