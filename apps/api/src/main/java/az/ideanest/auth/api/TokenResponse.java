package az.ideanest.auth.api;

/**
 * What a sign-in or refresh returns.
 *
 * @param accessToken the JWT to send as {@code Authorization: Bearer}. Kept in
 *     memory by a browser client, never in local storage — local storage is
 *     readable by any script that reaches the page
 * @param tokenType always {@code Bearer}. Present because clients build the
 *     header from it rather than hard-coding the word
 * @param expiresInSeconds lifetime of the access token, so a client can refresh
 *     before a request fails rather than after
 * @param refreshToken present only when the client asked for body delivery.
 *     Null for browsers, which get the cookie instead
 */
public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, String refreshToken) {

    public static TokenResponse of(String accessToken, long expiresInSeconds, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, refreshToken);
    }
}
