import { authorizedFetch } from '../api/client';
import { ApiError } from '../api/problem';

/**
 * The signed-in account, as `GET /v1/me` describes it — `MeController.MeResponse`.
 *
 * Six fields, all of them the reader's own. `email` is returned in full because the person
 * reading it is the person it belongs to, and `deletionScheduledAt` is here because an
 * account that has been closed and can still sign in has to say so — a client that hid it
 * would leave somebody assuming the deletion silently failed.
 */
export interface Session {
  readonly id: string;
  readonly email: string;
  readonly name: string;
  readonly slug: string;
  readonly emailVerified: boolean;
  /** Absent unless a deletion has been requested (§4.1 A-10). Absent means absent. */
  readonly deletionScheduledAt?: string | null;
}

/**
 * Reads the session, or answers `null` because there is not one.
 *
 * <h2>`authorizedFetch` is the bootstrap, not just the call</h2>
 *
 * On a fresh page load the access token is a module variable that has never been written,
 * so `authorizedFetch` finds nothing, spends the `HttpOnly` refresh cookie once — through
 * the single-flight in `lib/api/access-token.ts`, because refresh tokens rotate and two
 * concurrent refreshes would end the session family between them — and retries. That IS the
 * session bootstrap: there is no separate call to make.
 *
 * <h2>A 401 is an answer, and every other failure is not</h2>
 *
 * No cookie, an expired refresh token, or a session an administrator revoked all arrive as
 * a 401, and all of them mean the same thing to a caller: nobody is signed in. A 404 means
 * the token is genuine and the account behind it is gone, which `MeController` is explicit
 * about and which is also nobody signed in.
 *
 * Anything else is allowed to surface. A 500 from the service is not "signed out", and a
 * shell that quietly rendered its signed-out state during an outage would offer a Register
 * button to somebody who already has an account — and, worse, would send the route guard
 * below into redirecting them away from the page they were reading.
 */
export async function fetchSession(): Promise<Session | null> {
  try {
    const response = await authorizedFetch('/v1/me');

    if (response.status === 401 || response.status === 404) return null;
    if (!response.ok) {
      throw new ApiError(response.status, null, 'The account could not be read.');
    }

    return (await response.json()) as Session;
  } catch (cause) {
    /*
     * `authorizedFetch` throws its own `ApiError(401)` rather than returning a response when
     * there is no token at all and the refresh produced none — the ordinary state of every
     * visitor who has not signed in. That is the same answer as the 401 above.
     */
    if (cause instanceof ApiError && cause.status === 401) return null;
    throw cause;
  }
}
