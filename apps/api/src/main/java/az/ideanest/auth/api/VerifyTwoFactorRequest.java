package az.ideanest.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The second half of a sign-in.
 *
 * @param challenge what the first call returned
 * @param code the six digits from the authenticator, or null when a recovery
 *     code is being used instead
 * @param recoveryCode one of the codes printed at enrolment, hyphens optional.
 *     Only read when no {@code code} was sent, so that filling in both is one
 *     attempt rather than two
 * @param tokenDelivery {@code cookie} or {@code body}, exactly as on sign-in.
 *     The second call is where the tokens are actually issued, so the client
 *     has to say again where they should go
 */
public record VerifyTwoFactorRequest(
        @NotBlank(message = "A challenge is required") @Size(max = 128) String challenge,
        @Size(max = 16) String code,
        @Size(max = 40) String recoveryCode,
        String tokenDelivery) {

    public boolean wantsTokenInBody() {
        return "body".equalsIgnoreCase(tokenDelivery);
    }
}
