package az.ideanest.auth.api;

import jakarta.validation.constraints.Size;

/**
 * The body of a refresh or a sign-out.
 *
 * <p>Entirely optional: a browser sends nothing and the token is read from the
 * cookie. A native client sends the token it was given.
 *
 * @param refreshToken the token, for clients that hold it themselves
 */
public record RefreshRequest(@Size(max = 256) String refreshToken) {
}
