package az.ideanest.pledge.application;

import az.ideanest.fx.application.ExchangeRates;
import az.ideanest.user.application.UserAccounts;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What approximation a backer was shown, at the moment they committed — issue #327.
 *
 * <p>§21.2: "the rate used is stored on the pledge, for audit". This is the two questions
 * that answer joins — which currency does this person read amounts in, and what is one unit
 * of it worth right now — asked in one place so that {@code PledgeService#confirm} does not
 * have to know either module.
 *
 * <h2>Why this is in `pledge` and not in `fx`</h2>
 *
 * It couples an <em>account's preference</em> to a rate, and only one caller has ever wanted
 * that pair: the confirmation. {@code fx} deliberately knows nothing about accounts — its
 * {@code ExchangeRates} converts an amount into a currency it was handed, which is what
 * makes it usable from a public endpoint that has no reader — and putting a
 * {@code UserAccounts} dependency in it would make a module about arithmetic depend on a
 * module about people.
 *
 * <h2>It answers nothing far more often than it answers something</h2>
 *
 * Every account starts at the platform's own currency, so the ordinary pledge records no
 * rate at all — there was no approximation to keep, and V60 refuses one that would equal the
 * pledge's own currency. The same nothing comes back when the feature is off, when the
 * source has been unreachable past {@code ideanest.fx.max-age}, and when the account is
 * gone. Every one of those is "we cannot say what they were shown", and a rate invented for
 * any of them would be a false entry in an audit record.
 */
@Service
public class DisplayRates {

    private final UserAccounts users;
    private final ExchangeRates rates;

    public DisplayRates(UserAccounts users, ExchangeRates rates) {
        this.users = users;
        this.rates = rates;
    }

    /**
     * The rate to stamp on a pledge, or empty when there is nothing to stamp.
     *
     * @param backerId whose preference decides
     * @param pledgeCurrency what the pledge will actually be charged in
     */
    public Optional<DisplayRate> forBacker(UUID backerId, String pledgeCurrency) {
        return users.findById(backerId)
                .map(account -> account.currency())
                .flatMap(displayCurrency -> rates.rateFor(pledgeCurrency, displayCurrency)
                        .map(rate -> new DisplayRate(displayCurrency, rate)));
    }

    /**
     * One approximation, as a pledge records it.
     *
     * @param currency what the backer reads amounts in
     * @param rate units of the pledge's currency per ONE unit of {@code currency}
     */
    public record DisplayRate(String currency, BigDecimal rate) {
    }
}
