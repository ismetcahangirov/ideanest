package az.ideanest.payment.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * §9.4's capability record: what an adapter's provider can actually do.
 *
 * <p><strong>This is not documentation.</strong> §9.3 lists fourteen requirements and
 * says to confirm each in writing before signing, and the three that the whole design
 * rests on — R-01 card-on-file, R-02 merchant-initiated, R-03 scheme chaining — are
 * asserted here at start-up by {@code PaymentProviders}. An adapter whose provider
 * cannot do them is refused rather than registered, because §9.1 has already
 * established that without them "the model collapses": a platform that holds a
 * payment obligation for sixty days and then discovers it cannot collect has taken
 * money it cannot take.
 *
 * <p>The alternative was to check at the point of the first charge, and it is worse
 * in the way that matters. The first charge happens at a campaign's close, which is
 * the single least recoverable moment on the platform: ten thousand backers have
 * been told their campaign succeeded and the creator has been told the money is
 * coming. Discovering a missing capability then is discovering it in front of
 * everybody.
 *
 * @param cardOnFile R-01. Whether a card can be tokenised and charged later without
 *     the card data ever reaching this service. Required
 * @param merchantInitiated R-02. Whether that later charge can be made with the
 *     backer absent. Required, and the single hardest thing on §9.3's list to get in
 *     writing — it is what #60 exists to obtain
 * @param preAuthHoldDays R-03's neighbour and §9.1's rejected approach: how long the
 *     provider's authorisation holds last, or {@code null} when it does not offer
 *     them. Kept because a provider that held an authorisation for sixty days would
 *     change the design, and a number nobody recorded is a question that gets asked
 *     again every year
 * @param schemeChaining R-03. Whether the merchant-initiated charge can reference the
 *     original customer-initiated transaction, which scheme rules require. Required:
 *     without it the charge is submitted as though the backer were present, and the
 *     issuer is entitled to decline it and to treat a pattern of them as fraud
 * @param splitPayment R-10, optional. Whether §9.5's distribution can be performed by
 *     the provider rather than by the payout run. Optional means optional — the
 *     ledger is the platform's record either way, and a provider that splits changes
 *     who moves the money and not who accounts for it
 * @param partialRefund R-06. Whether less than the whole of a charge can be returned.
 *     Not required at registration, because §9.7's scenarios are all full refunds;
 *     #67 is what will read it, and a provider without it makes partial refunds a
 *     product limitation rather than a bug
 * @param wallets R-12, possibly empty
 * @param currencies R-11: the ISO 4217 codes the provider settles in. Checked per
 *     charge rather than at start-up, because a provider that cannot take one
 *     currency is still the right provider for every campaign in the others
 */
public record ProviderCapabilities(
        boolean cardOnFile,
        boolean merchantInitiated,
        Integer preAuthHoldDays,
        boolean schemeChaining,
        boolean splitPayment,
        boolean partialRefund,
        Set<WalletType> wallets,
        Set<String> currencies) {

    public ProviderCapabilities {
        // Defensive copies of both, and not merely for immutability. These sets are
        // read on every charge to decide whether a currency is supported, and an
        // adapter that handed over a mutable set it went on holding could change the
        // answer between the check and the call.
        wallets = wallets == null ? Set.of() : Set.copyOf(wallets);
        currencies = currencies == null ? Set.of() : Set.copyOf(currencies);

        if (preAuthHoldDays != null && preAuthHoldDays < 0) {
            throw new IllegalArgumentException(
                    "A hold cannot last a negative number of days, and one claims " + preAuthHoldDays);
        }
        for (String currency : currencies) {
            Objects.requireNonNull(currency, "A supported currency cannot be null");
            if (!currency.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException(
                        "A supported currency is an ISO 4217 code in upper case, and one is '" + currency + "'");
            }
        }
    }

    /**
     * Whether this provider can do the three things §9.2's design cannot work without.
     *
     * <p>Named for the question rather than for the fields, so that the answer is
     * stated in one place and every caller asks it the same way. {@link #missing()} is
     * what says which one is absent, because a refusal that does not name the
     * capability leaves whoever reads the log comparing fourteen booleans by hand.
     */
    public boolean supportsStoredCardCollection() {
        return cardOnFile && merchantInitiated && schemeChaining;
    }

    /** Which of the three required capabilities are absent, in §9.3's order. Empty when none are. */
    public List<String> missing() {
        List<String> absent = new ArrayList<>(3);
        if (!cardOnFile) {
            absent.add("R-01 card tokenisation");
        }
        if (!merchantInitiated) {
            absent.add("R-02 merchant-initiated transactions");
        }
        if (!schemeChaining) {
            absent.add("R-03 scheme transaction chaining");
        }
        return List.copyOf(absent);
    }

    /** Whether the provider settles in this currency. */
    public boolean supports(String currency) {
        return currency != null && currencies.contains(currency);
    }
}
