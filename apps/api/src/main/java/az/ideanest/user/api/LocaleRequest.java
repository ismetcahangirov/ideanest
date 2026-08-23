package az.ideanest.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * §4.2's P-10, as a request body: the language to write to this person in (#324).
 *
 * <p><strong>A string against a pattern rather than an enum</strong>, unlike
 * {@link ProfileVisibilityRequest} beside it, and the difference is where the vocabulary
 * lives. Profile visibility is a domain concept this service reasons about — it decides
 * who may read a page — so it is a Java type. A locale is a tag this service stores and
 * hands back: nothing here branches on it, {@code users.locale} is {@code text}, and
 * {@code auth}'s {@code RegistrationRequest} already takes it as a string against this
 * same pattern. An enum would be a third spelling of §21.1's four languages, and the
 * one that falls behind is whichever is not next to the check constraint.
 *
 * <p><strong>The same pattern as registration, deliberately.</strong> {@code az|en|ru|tr}
 * is {@code users_locale_supported}'s list, and the reason to repeat it here rather than
 * let the constraint refuse is what the two refusals look like: this one is a 400 naming
 * the field, and the one a layer down is a constraint violation surfacing as a 500.
 *
 * @param locale required, and a language §21.1 has. Unlike registration — where an unstated
 *     locale means Azerbaijani, because somebody signing up has not been asked — absent is
 *     not a default here. This request exists only to change the setting, so a body with no
 *     locale in it is a client whose serialiser dropped the field, and answering 204 to
 *     that would report a language change that did not happen to somebody who would then
 *     find out by reading their next email in the wrong language
 */
public record LocaleRequest(
        @NotBlank(message = "A language is required")
                @Pattern(regexp = "az|en|ru|tr", message = "That language is not supported")
                String locale) {
}
