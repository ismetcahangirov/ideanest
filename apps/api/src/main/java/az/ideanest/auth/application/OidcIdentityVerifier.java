package az.ideanest.auth.application;

import az.ideanest.auth.domain.IdentityProvider;
import az.ideanest.shared.EmailAddress;

/**
 * Establishing who an ID token is actually about.
 *
 * <p>An interface so that the JOSE machinery stays in one class, and so that
 * everything above this line deals in "a person the provider vouched for".
 *
 * <p><strong>Nothing the client says about the user is an input here.</strong>
 * The client sends a token and, at most, the nonce it bound that token to. Who
 * the person is comes out of the token's claims after the signature has been
 * checked against the provider's published keys — a client that could assert its
 * own subject or its own address could sign in as anybody.
 */
public interface OidcIdentityVerifier {

    /**
     * @param provider which provider minted the token
     * @param idToken the compact JWT the client obtained from the provider
     * @param presentedNonce the nonce the client says it put in its
     *     authorisation request, or {@code null}. Checked against the token's
     *     {@code nonce} claim
     * @throws AuthenticationFailedException if the token is not a valid,
     *     unexpired, correctly signed token issued to us by that provider
     * @throws ProviderNotConfiguredException if the provider is known but not
     *     enabled in this environment
     */
    VerifiedIdentity verify(IdentityProvider provider, String idToken, String presentedNonce);

    /**
     * What the provider vouched for.
     *
     * @param subject the {@code sub} claim. The account, and the only field the
     *     link is keyed on
     * @param email the address the provider asserted, or {@code null} if it
     *     asserted none
     * @param emailVerified whether the provider says it has proven that address.
     *     Both Google and Apple can send this as the string {@code "true"}
     *     rather than a boolean, so the reading of it is deliberate and not
     *     incidental
     * @param privateEmail Apple's {@code is_private_email}: the address is a
     *     relay that forwards until the user revokes it
     * @param name a display name if the provider supplied one. Apple never does
     *     in the token
     */
    record VerifiedIdentity(
            IdentityProvider provider,
            String subject,
            EmailAddress email,
            boolean emailVerified,
            boolean privateEmail,
            String name) {
    }
}
