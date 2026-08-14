package az.ideanest.auth.api;

/**
 * What a sign-in returns when the password was right and a code is still owed.
 *
 * @param twoFactorRequired always true. A flag rather than a bare challenge so
 *     that a client branches on a field it can see in the response, instead of
 *     on the absence of {@code accessToken}
 * @param challenge the value to send to {@code /v1/auth/2fa/verify} with the
 *     code. Single use, and dead within minutes
 * @param expiresInSeconds how long that is, so the form can say so rather than
 *     letting the user find out by being refused
 */
public record TwoFactorChallengeResponse(boolean twoFactorRequired, String challenge, long expiresInSeconds) {

    public static TwoFactorChallengeResponse of(String challenge, long expiresInSeconds) {
        return new TwoFactorChallengeResponse(true, challenge, expiresInSeconds);
    }
}
