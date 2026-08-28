import { errorFrom } from '../api/problem';
import { postToAuth } from './post';

/**
 * §4.1's A-06 — setting a new password without knowing the old one. Issue #271.
 *
 * <h2>Neither call is authorised, and that is the situation rather than an omission</h2>
 *
 * Somebody asking for a reset is by definition somebody who cannot sign in, so there is no
 * bearer token to send and `authorizedFetch` is the wrong shape for the same reason
 * `lib/auth/api.ts` gives about the sign-in itself: it throws when there is no token.
 * `postToAuth` is the transport — relative, same-origin, `X-IdeaNest-Client`, `no-store` —
 * and it is the only way anything in this application posts to `/v1/auth`.
 *
 * <h2>The request tells the caller nothing, and this module cannot make it tell them more</h2>
 *
 * `POST /v1/auth/forgot-password` answers **202 whether or not the address has an account**.
 * `PasswordResetService` gives the reason at length: an endpoint that answered "no such
 * account" is an enumeration oracle, and the list it produces — which people on a breach list
 * have accounts here — is the list somebody wants before writing a phishing email.
 *
 * That decision only holds if the client honours it, and honouring it has a consequence that
 * has to be stated plainly rather than worked around: **there is no success to report, only a
 * request that was accepted.** {@link requestPasswordReset} therefore returns `void` and has
 * nothing to return; the screen above it says "if that address has an account, a link is on
 * its way" rather than "check your inbox", because the second sentence is a claim about an
 * account this application was deliberately not told about.
 *
 * It also means nothing is sent to an address with no account — not even a "somebody asked
 * about you" notice, which is what registration does send. The address here is whatever was
 * typed into a public form, and mailing it would make this platform a delivery service for
 * strangers.
 *
 * <h2>The link is one hour and one use, and both numbers are the screen's to say</h2>
 *
 * `auth.reset.lifetime` exists so that the two screens cannot come to two different
 * answers about it. `ideanest.auth.password-reset-token-ttl` is `PT1H`, deliberately not the
 * twenty-four hours a verification link gets: that link proves an address, this one replaces a
 * credential, and a forwarded message should stop being a key to the account long before it
 * stops being a proof of the mailbox.
 *
 * <h2>A rejected password does not spend the link</h2>
 *
 * `PasswordResetService.reset` checks the policy **before** it claims the token — "a password
 * the policy refuses would otherwise burn the link on the way to a 400, and the person fixing
 * their typo would find the link dead, which is the reset flow's most common support ticket,
 * self-inflicted". So a `weak-password` refusal is recoverable in place: the same token may be
 * submitted again with a better password, and the screen must offer that rather than sending
 * somebody back to ask for another email.
 */

/**
 * The query parameter the reset link carries, and the one the confirmation screen reads.
 *
 * NAMED HERE BECAUSE IT IS A CONTRACT WITH SOMETHING OUTSIDE THIS REPOSITORY. The service
 * issues the token and the message that carries it; the link in that message resolves against
 * `ideanest.notification.email.base-url`, which is this application's origin. So the agreement
 * between the two halves is a path and the name of one parameter — the same arrangement
 * `/verify-email` documents, spelled the same way so that a reader of one finds the other.
 */
export const RESET_TOKEN_PARAM = 'token';

/*
 * HOW LONG A RESET LINK WORKS IS NOW `auth.reset.lifetime` IN THE CATALOGUE — issue #324.
 *
 * It used to be `RESET_LINK_LIFETIME`, a constant here, and the reason it existed is
 * unchanged: it is one fact about one link that two screens print, and keeping it as `3600`
 * to be formatted at each call site is how "one hour" and "60 minutes" end up on two pages
 * describing the same thing. What changed is that the phrase has to exist in four languages,
 * and a constant in a module cannot.
 *
 * The single-source property moved with it rather than being lost: `passwordResetCopyFrom`
 * and `passwordResetConfirmCopyFrom` both read `reset.lifetime`, so the two screens still
 * cannot come to two different answers. It is still a phrase rather than a number of seconds,
 * because nothing on either screen computes with it — both put it in a sentence, and in
 * Azerbaijani and Russian that sentence declines the noun.
 */

/**
 * Asks for a reset link — `POST /v1/auth/forgot-password`.
 *
 * **202 always.** See the module comment: there is no outcome to branch on, and a client that
 * invented one would be reconstructing the oracle the endpoint was arranged to close.
 *
 * What can still fail is §17.3's rate limit, which is keyed per source address *and* per email
 * address. The second key is not about secrecy — the response is identical either way — but
 * about not letting somebody be buried in reset links until they stop reading their mail. A
 * 429 arrives as an `ApiError` and `describeAuthFailure` turns it into the sentence with the
 * wait in it.
 */
export async function requestPasswordReset(email: string): Promise<void> {
  const response = await postToAuth('/v1/auth/forgot-password', { email });
  if (!response.ok) throw await errorFrom(response);
}

/**
 * Spends the link and sets the password — `POST /v1/auth/reset-password`.
 *
 * THE TOKEN GOES IN THE BODY, for `verifyEmail`'s reason and with more at stake: this value
 * does not merely prove a mailbox, it replaces a credential. The email can only send somebody
 * to a URL, so the token does arrive in this application's own address bar and that is
 * unavoidable; what the arrangement buys is that it never leaves the browser as a request the
 * service — or anything between the two — writes to a log.
 *
 * A refusal throws the service's own problem detail, and the screen prints that sentence
 * rather than guessing. The distinction matters here more than it does for a verification
 * link, because the service writes **three different sentences** and they are not
 * interchangeable: "This link is not valid.", "This link has expired. Ask for a new one." and
 * "This link has already been used." all arrive as `invalid-verification-link`, and only the
 * service knows which. A client that collapsed them into one message would tell somebody whose
 * link expired eleven minutes ago that they had already used it.
 *
 * On success every session the account had is revoked — a reset is asked for precisely when
 * the old password is believed to be known — and there is no session here to lose. The screen
 * says so and offers the sign-in.
 */
export async function resetPassword(token: string, password: string): Promise<void> {
  const response = await postToAuth('/v1/auth/reset-password', { token, password });
  if (!response.ok) throw await errorFrom(response);
}
