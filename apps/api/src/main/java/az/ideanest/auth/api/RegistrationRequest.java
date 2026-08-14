package az.ideanest.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to register.
 *
 * <p>An explicit request type, not the entity. Binding input straight onto an
 * entity is how a field nobody meant to expose — {@code emailVerifiedAt}, say —
 * becomes settable by whoever is willing to add it to the JSON.
 *
 * @param email the address to register and verify
 * @param password the raw password. Length is checked by the policy rather than
 *     here, so that one place decides what is acceptable and the message a user
 *     sees is the same wherever a password is set
 * @param name the display name. A slug is derived from it
 * @param locale which language to write to this person in. Optional
 */
public record RegistrationRequest(
        @NotBlank(message = "An email address is required")
                @Email(message = "That is not an email address")
                @Size(max = 254, message = "That address is too long")
                String email,
        @NotBlank(message = "A password is required") String password,
        @NotBlank(message = "A name is required")
                @Size(max = 80, message = "A name may not exceed 80 characters")
                String name,
        @Pattern(regexp = "az|en|ru|tr", message = "That language is not supported") String locale) {

    /** Azerbaijani is the primary language, so it is what an unstated locale means. */
    public String localeOrDefault() {
        return locale == null || locale.isBlank() ? "az" : locale;
    }
}
