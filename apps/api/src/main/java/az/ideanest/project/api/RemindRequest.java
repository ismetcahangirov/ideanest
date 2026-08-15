package az.ideanest.project.api;

/**
 * "Tell me when this opens."
 *
 * <p>The body is optional in full: a signed-in caller sends nothing at all, and
 * the reminder is registered against their account. The address here is for
 * somebody with no account, which is the case a pre-launch page exists to serve —
 * asking a stranger to register before they may ask to be told about something is
 * how a follower list stays empty.
 *
 * <p><strong>Deliberately not {@code @Email}-annotated.</strong> {@code
 * EmailAddress} is the one definition of what an address is, and it is
 * intentionally loose: the real test of an address is whether the message arrives.
 * A bean-validation pattern here would be a second, stricter definition, and the
 * first address it refused would be a valid one nobody could explain.
 *
 * @param email where to write. Ignored when the caller is signed in — the
 *     account's own verified address is used instead, because an address taken
 *     from the request is one the caller chose
 */
public record RemindRequest(String email) {
}
