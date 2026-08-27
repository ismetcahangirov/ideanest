package az.ideanest.user.api;

import az.ideanest.fx.application.ExchangeRates;
import az.ideanest.user.application.UserAccounts;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.2's P-10, the currency half: which currency this account reads amounts in — #327.
 *
 * <p>The sibling of {@code LocalePreferenceController}, and the two are deliberately
 * separate endpoints rather than one {@code PATCH /v1/me/preferences}. Changing a language
 * and changing a currency are separate acts a person takes at separate moments, and an
 * endpoint that took both would have to decide what an absent field means — leave it, or
 * clear it — which is the ambiguity a field-per-endpoint has none of.
 *
 * <h2>THE VALIDATION IS HERE BECAUSE THE ANSWER CHANGES</h2>
 *
 * §21.1's four languages are a decision this repository made and a check constraint holds,
 * so {@code LocaleRequest} can carry the list in a pattern. The display currencies cannot:
 * which ones are available is a property of what a central bank published and when the
 * platform last reached it. A currency offered last week is not offered this week if the
 * source stopped publishing it, and a pattern would say otherwise.
 *
 * <p>So the check is against {@link ExchangeRates#available()} — what the platform can
 * honour <em>right now</em> — and the refusal carries that list, because it is the one thing
 * the client needs and the one thing it cannot work out for itself.
 *
 * <h2>The platform's own currency is always allowed</h2>
 *
 * It is how somebody turns the preference off. Setting it is not a conversion — there is
 * nothing to approximate — so it is accepted whatever the rate source is doing, including on
 * a deployment where the feature is switched off entirely. A reader who cannot undo a
 * setting because a third party is down is a reader stuck with a stale approximation.
 */
@RestController
public class CurrencyPreferenceController {

    private final UserAccounts users;
    private final ExchangeRates rates;

    public CurrencyPreferenceController(UserAccounts users, ExchangeRates rates) {
        this.users = users;
        this.rates = rates;
    }

    @PatchMapping("/v1/me/currency")
    public ResponseEntity<Void> setCurrency(
            @AuthenticationPrincipal Jwt accessToken, @Valid @RequestBody CurrencyRequest request) {

        String currency = request.currency();
        List<String> available = availableCurrencies();
        if (!available.contains(currency)) {
            throw new UnsupportedDisplayCurrencyException(currency, available);
        }

        UUID accountId = UUID.fromString(accessToken.getSubject());
        users.setCurrency(accountId, currency);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * What a reader may choose: the platform's own currency, and every one with a fresh rate.
     *
     * <p>The base currency first, because it is the default and the way out of a choice
     * somebody regrets — see the class note.
     */
    private List<String> availableCurrencies() {
        List<String> available = new ArrayList<>();
        available.add(rates.baseCurrency());
        rates.available().forEach(quote -> available.add(quote.currency()));
        return List.copyOf(available);
    }
}
