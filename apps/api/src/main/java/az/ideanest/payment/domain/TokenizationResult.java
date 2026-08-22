package az.ideanest.payment.domain;

import java.util.Objects;

/**
 * What came back once the backer finished §9.2's phase one.
 *
 * <p>Two halves, and the constructor holds them apart. An approval carries the token
 * and the scheme transaction identifier that phase two will be chained to, and no
 * failure code; a decline carries the code and neither of the other two. A result
 * that carried both would be a card the platform believes it can charge and a reason
 * it was refused, which is the pair that eventually charges somebody who was told no.
 *
 * @param outcome what the provider decided
 * @param token the provider's reference to the card, on an approval
 * @param schemeTransactionId §9.3's R-03, on an approval. Kept from this call and used
 *     by every later one: it is the identifier the merchant-initiated charge chains
 *     to, and it comes from here or from nowhere
 * @param brand what to show the backer on their saved cards. Display only
 * @param last4 the same, and the most that may ever be stored
 * @param expiryMonth the same. Used for one thing beyond display — §8.4's
 *     {@code token-cleaner} and §4.5's "your card expires before this campaign closes"
 * @param expiryYear the same
 * @param failureCode why the verification was refused, on a decline
 * @param failureMessage the same in words, for support
 */
public record TokenizationResult(
        ProviderOutcome outcome,
        String token,
        String schemeTransactionId,
        String brand,
        String last4,
        Integer expiryMonth,
        Integer expiryYear,
        String failureCode,
        String failureMessage) {

    public TokenizationResult {
        Objects.requireNonNull(outcome, "A tokenisation result says what the provider decided");
        if (outcome == ProviderOutcome.APPROVED) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("An approved verification that saved no card saved nothing");
            }
            if (schemeTransactionId == null || schemeTransactionId.isBlank()) {
                // The failure this refuses is silent and sixty days later: a card saved
                // without R-03's identifier looks fine on the backer's saved cards and
                // cannot legally be charged at the campaign's close.
                throw new IllegalArgumentException(
                        "§9.3's R-03 requires the scheme transaction identifier of the verification");
            }
        }
        if ((outcome == ProviderOutcome.DECLINED) == (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException(
                    "A declined verification says why, and one that was not declined does not");
        }
        if (last4 != null && !last4.matches("^[0-9]{4}$")) {
            throw new IllegalArgumentException("The last four digits are four digits");
        }
    }
}
