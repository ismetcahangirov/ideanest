package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to sign in with Google or Apple.
 *
 * <p>Note what is <strong>not</strong> here: no subject, no email, no
 * {@code emailVerified}. Everything about who the person is comes out of the
 * signed token. A field on this record that the server believed would be a field
 * an attacker could set.
 *
 * @param idToken the compact JWT the client obtained from the provider, through
 *     a native SDK or the web flow. Bounded because it is parsed before it is
 *     trusted, and an unbounded input is work we do on request
 * @param nonce the nonce the client put in its authorisation request. Apple's
 *     native flow hashes it before it reaches the token, so what belongs here is
 *     whatever the client actually sent to the provider — the SHA-256 on iOS,
 *     the raw string on the web. It is compared, not interpreted
 * @param name a display name, used only if this sign-in creates an account.
 *     <strong>Apple's only chance to give us a name.</strong> It appears in the
 *     body of the first authorisation response and never again, not even on a
 *     later sign-in, so a client that does not forward it then has lost it. It
 *     never modifies an existing account: it is client-supplied
 * @param locale which language to write to a newly created account in
 * @param deviceLabel what to call this device in the user's session list
 * @param tokenDelivery {@code cookie} or {@code body}, exactly as on password
 *     sign-in. A browser must not be handed a refresh token its own scripts can
 *     read
 */
public record OAuthSignInRequest(
        @NotBlank(message = "An ID token is required")
                @Size(max = 8192, message = "That is not an ID token")
                String idToken,
        @Size(max = 512) String nonce,
        @Size(max = 80, message = "A name may not exceed 80 characters") String name,
        @Pattern(regexp = "az|en|ru|tr", message = "That language is not supported") String locale,
        @Size(max = 120, message = "That device name is too long") String deviceLabel,
        String tokenDelivery) {

    /** Azerbaijani is the primary language, so it is what an unstated locale means. */
    public String localeOrDefault() {
        return locale == null || locale.isBlank() ? "az" : locale;
    }

    public boolean wantsTokenInBody() {
        return "body".equalsIgnoreCase(tokenDelivery);
    }
}
