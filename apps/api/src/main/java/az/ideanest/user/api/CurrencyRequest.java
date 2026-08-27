package az.ideanest.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * §4.2's P-10, currency half, as a request body: the currency to show amounts in (#327).
 *
 * <p><strong>A shape and not a list</strong>, unlike {@link LocaleRequest} beside it, and
 * the difference is where the vocabulary comes from. §21.1's four languages are a decision
 * this repository made and a check constraint holds; the display currencies are a property
 * of the <em>deployment's rate source</em> — a currency that was offered last week is not
 * this week if the source stopped publishing it. A regular expression here would be a third
 * copy of that list, and the one that falls behind is whichever is furthest from the source.
 *
 * <p>So this refuses what is not a currency code at all, which is a client bug, and
 * {@code CurrencyPreferenceController} refuses what the platform cannot honour, which is a
 * 400 naming the currencies that are available today.
 *
 * @param currency required. Unlike registration — where an unstated currency means the
 *     platform's own, because somebody signing up has not been asked — absent is not a
 *     default here. This request exists only to change the setting, so a body without it is
 *     a client whose serialiser dropped the field, and answering 204 to that would report a
 *     change that did not happen
 */
public record CurrencyRequest(
        @NotBlank(message = "A currency is required")
                @Pattern(regexp = "[A-Z]{3}", message = "A currency is a three-letter ISO 4217 code")
                String currency) {
}
